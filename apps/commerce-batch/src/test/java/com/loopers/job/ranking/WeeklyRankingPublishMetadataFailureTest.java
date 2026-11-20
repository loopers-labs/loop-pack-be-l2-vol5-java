package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.PublishRankingTasklet;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.job.ranking.WeeklyRankingTestConfig.HookedWeeklyRankingRepository;
import com.loopers.job.reconcile.ReconcileTestConfig.MutableClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// w9t: 공개 TX가 commit된 뒤 Spring Batch가 공개 Step 실행 기록을 저장하다 실패하는 장면.
// 실패 주입은 테스트 대역 JobRepository(@Primary, 실제 저장소에 위임)로만 한다. 공개 Step(publishWeeklyRanking)의 update 가운데
// TX가 없고 종료 시각이 정해진 것(AbstractStep 마무리의 마지막 저장)만 한 번 실패시킨다. tasklet TX 안의 update는 건드리지 않는다.
// 실패 직전에 TX가 없었는지와 다른 연결로 읽은 pointer를 기록해 "commit 뒤"임을 보인다.
// 고객 API 쿼리는 api JdbcWeeklyRankingReader의 문장을 그대로 옮겨 실행한다. products·brands는 api 엔티티 테이블이라 이 테스트가 만들고 지운다.
@SpringBootTest
@Import({WeeklyRankingTestConfig.class, WeeklyRankingPublishMetadataFailureTest.FailingRepositoryConfig.class})
@TestPropertySource(properties = {
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "spring.batch.job.enabled=false",
    "commerce.ranking.chunk-size=2"
})
class WeeklyRankingPublishMetadataFailureTest {
    private static final List<String> FULL_RANKING = List.of("1/7/5", "2/9/5", "3/8/1");
    private static final String RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    // api JdbcWeeklyRankingReader.RANKED_SQL과 같은 문장.
    private static final String API_SQL = "select snapshot.snapshot_id, snapshot.frozen_at, result.calc_rank,"
        + " result.product_id, product.name, product.price, product.brand_id, brand.name as brand_name, result.score,"
        + " product.stock"
        + " from ranking_result result"
        + " join ranking_snapshot snapshot on snapshot.snapshot_id = result.snapshot_id"
        + " join products product on product.id = result.product_id and product.deleted = false"
        + " join brands brand on brand.id = product.brand_id"
        + " where result.snapshot_id = (select publication.snapshot_id from ranking_publication publication"
        + " where publication.period_start = ?)"
        + " order by result.calc_rank"
        + " limit ?";

    @Autowired private JobLauncher jobLauncher;
    @Autowired private Job weeklyRanking;
    @Autowired private JobExplorer jobExplorer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private HookedWeeklyRankingRepository repository;
    @Autowired private FailingStepUpdate failingStepUpdate;

    private RankingRows rows;
    private LocalDate period;

    @BeforeEach
    void prepare() {
        rows = new RankingRows(jdbc);
        rows.clearSource();
        clock.set(Instant.parse("2026-08-24T00:10:00Z"));
        repository.disarm();
        failingStepUpdate.disarm();
        period = RankingRows.randomMonday();
        jdbc.execute("create table if not exists brands (id bigint not null, name varchar(100) not null, primary key (id))");
        jdbc.execute("create table if not exists products (id bigint not null, brand_id bigint not null, name varchar(100) not null,"
            + " price bigint not null, stock int not null, deleted bit not null, primary key (id))");
        jdbc.update("delete from products");
        jdbc.update("delete from brands");
        jdbc.update("insert into brands(id, name) values (1, '브랜드')");
        for (long productId = 7; productId <= 9; productId++) {
            jdbc.update("insert into products(id, brand_id, name, price, stock, deleted) values (?, 1, ?, 1000, 3, false)",
                productId, "상품" + productId);
        }
    }

    @AfterEach
    void clean() {
        failingStepUpdate.disarm();
        repository.disarm();
        rows.clearSource();
        jdbc.execute("drop table if exists products");
        jdbc.execute("drop table if exists brands");
    }

    @Test
    @DisplayName("공개 TX commit 뒤 공개 Step 기록 저장이 실패하면 pointer·완성 랭킹·API 쿼리는 r2이고, Batch 기록은 job UNKNOWN·공개 Step STARTED로 남아 같은 parameter 재실행이 실행 중으로 거절된다")
    void metadataFailureAfterPublishCommit() throws Exception {
        rows.insertFixture(period);
        assertThat(run(id(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        failingStepUpdate.armFor(period);

        JobExecution failed = run(id(2));

        // 실패 주입 시점: TX가 없었고, 다른 연결로 읽은 pointer는 이미 r2였다(공개 TX commit 뒤).
        assertThat(failingStepUpdate.injected()).isTrue();
        assertThat(failingStepUpdate.transactionActiveAtFailure()).isFalse();
        assertThat(failingStepUpdate.pointerAtFailure()).isEqualTo(id(2));
        // 공개 상태: pointer = r2, r2 완성 랭킹이 있고, API와 같은 쿼리도 r2를 읽는다.
        assertThat(rows.pointer(period)).contains(id(2));
        assertThat(rows.results(id(2))).containsExactlyElementsOf(FULL_RANKING);
        assertThat(jdbc.queryForList(API_SQL, period, 100)).extracting(row -> row.get("snapshot_id") + "/" + row.get("product_id"))
            .containsExactly(id(2) + "/7", id(2) + "/9", id(2) + "/8");
        // Batch 기록: 저장된 그대로 단언한다.
        JobExecution stored = jobExplorer.getJobExecution(failed.getId());
        StepExecution storedPublish = publishStep(stored);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(stored.getStatus()).isEqualTo(BatchStatus.UNKNOWN);
        assertThat(stored.getExitStatus().getExitCode()).isEqualTo("UNKNOWN");
        assertThat(storedPublish.getStatus()).isEqualTo(BatchStatus.STARTED);
        assertThat(storedPublish.getExecutionContext().getString(PublishRankingTasklet.RESULT_CHECKSUM_KEY)).isEqualTo(RESULT_CHECKSUM);

        // 같은 parameter로 다시 실행: 공개 Step 기록이 STARTED로 남아 Spring Batch가 "실행 중"으로 보고 거절한다. 공개 상태는 그대로다.
        Throwable rejected = catchThrowable(() -> run(id(2)));

        assertThat(rejected).isInstanceOf(JobExecutionAlreadyRunningException.class)
            .hasMessageContaining("A job execution for this job is already running")
            .hasMessageContaining("status=UNKNOWN");
        assertThat(rows.pointer(period)).contains(id(2));
        assertThat(rows.results(id(2))).containsExactlyElementsOf(FULL_RANKING);
    }

    private String id(int revision) {
        return RankingRows.snapshotId(period, revision);
    }

    private JobExecution run(String snapshotId) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
            .addString("periodStart", period.toString())
            .addString("snapshotId", snapshotId)
            .toJobParameters();
        return jobLauncher.run(weeklyRanking, parameters);
    }

    private static StepExecution publishStep(JobExecution execution) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(WeeklyRankingJobConfig.PUBLISH_STEP)).findFirst().orElseThrow();
    }

    // 공개 Step의 update(StepExecution) 가운데 TX가 없고 종료 시각이 정해진 것(마무리 저장)만 한 번 실패시킨다.
    static class FailingStepUpdate {
        static final String FAILURE = "주입된 공개 Step 기록 저장 실패";

        private final DataSource dataSource;
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile LocalDate period;
        private volatile boolean injected;
        private volatile boolean transactionActiveAtFailure;
        private volatile String pointerAtFailure;

        FailingStepUpdate(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void armFor(LocalDate period) {
            this.period = period;
            injected = false;
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        boolean injected() {
            return injected;
        }

        boolean transactionActiveAtFailure() {
            return transactionActiveAtFailure;
        }

        String pointerAtFailure() {
            return pointerAtFailure;
        }

        void beforeUpdate(StepExecution stepExecution) {
            if (!WeeklyRankingJobConfig.PUBLISH_STEP.equals(stepExecution.getStepName()) || stepExecution.getEndTime() == null
                || TransactionSynchronizationManager.isActualTransactionActive() || !armed.compareAndSet(true, false)) {
                return;
            }
            injected = true;
            transactionActiveAtFailure = TransactionSynchronizationManager.isActualTransactionActive();
            pointerAtFailure = readPointer();
            throw new IllegalStateException(FAILURE);
        }

        private String readPointer() {
            try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(
                    "select snapshot_id from ranking_publication where period_start = ?")) {
                select.setObject(1, period);
                try (ResultSet resultSet = select.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @TestConfiguration
    static class FailingRepositoryConfig {
        @Bean
        FailingStepUpdate failingStepUpdate(DataSource dataSource) {
            return new FailingStepUpdate(dataSource);
        }

        // 실제 JobRepository에 모두 위임하고, update(StepExecution) 직전에만 대역 판단을 끼운다.
        @Bean
        @Primary
        JobRepository failingJobRepository(@Qualifier("jobRepository") JobRepository jobRepository,
            FailingStepUpdate failingStepUpdate) {
            return (JobRepository) Proxy.newProxyInstance(JobRepository.class.getClassLoader(), new Class<?>[] {JobRepository.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName()) && args != null && args.length == 1
                        && args[0] instanceof StepExecution stepExecution) {
                        failingStepUpdate.beforeUpdate(stepExecution);
                    }
                    try {
                        return method.invoke(jobRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
        }
    }
}
