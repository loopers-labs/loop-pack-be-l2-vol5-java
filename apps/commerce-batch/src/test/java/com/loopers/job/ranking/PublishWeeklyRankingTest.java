package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.PublishWeeklyRankingTasklet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * ③ 완성본 공개. 공개 트랜잭션 rollback, 같은 공개의 재실행, 오래된 revision의 역행 차단, 빈 주간 결과를 실제 DB로 확인한다.
 * RankingJobRestartTest와 같은 설정·같은 spy라 Spring context를 함께 쓴다.
 */
@SpringBootTest(properties = {
    "spring.batch.job.enabled=false",
    "spring.batch.job.name=" + WeeklyRankingJobConfig.JOB_NAME,
    "ranking.weekly.chunk-size=2"
})
class PublishWeeklyRankingTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(WeeklyRankingJobConfig.JOB_NAME)
    private Job job;

    @MockitoSpyBean
    private RankingSnapshotStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private DataSource dataSource;

    private RankingFixtures fixtures;

    private final AtomicLong failCalculationOnceAt = new AtomicLong();
    private final AtomicBoolean failPointerOnce = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        fixtures = new RankingFixtures(dataSource);
        fixtures.clearData();
        failCalculationOnceAt.set(0);
        failPointerOnce.set(false);
        doAnswer(invocation -> {
            long productId = invocation.getArgument(1);
            if (failCalculationOnceAt.compareAndSet(productId, 0)) {
                throw new IllegalStateException("상품 " + productId + " 계산 직전 실패(테스트)");
            }
            return invocation.callRealMethod();
        }).when(store).sumQuantity(anyString(), anyLong());
        // 완성 ranking 저장·COMPLETED 표시 뒤, pointer를 바꾸기 직전에 공개 트랜잭션을 실패시킨다.
        doAnswer(invocation -> {
            if (failPointerOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("pointer 변경 직전 실패(테스트)");
            }
            return invocation.callRealMethod();
        }).when(store).findPublicationForUpdate(any(LocalDate.class));
    }

    @DisplayName("공개 트랜잭션이 실패하면 새 ranking·완성 표시·pointer가 모두 rollback되어 이전 공개본이 그대로이고, 재시작하면 새 완성본으로 바뀐다.")
    @Test
    void keepsPreviousPublication_whenPublishTransactionFails() throws Exception {
        fixtures.assignmentWeek("2026-11-02T00:00:00Z");
        jobLauncher.run(job, params("2026-11-02", "weekly-20261102-r1"));
        fixtures.activity(46, 8, 7, "2026-11-03T05:00:00Z");

        failPointerOnce.set(true);
        JobExecution failed = jobLauncher.run(job, params("2026-11-02", "weekly-20261102-r2"));

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(fixtures.ranking("weekly-20261102-r2")).isEmpty();
        assertThat(fixtures.snapshot("weekly-20261102-r2").get("status")).isEqualTo("FROZEN");
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20261102-r1");

        JobExecution restarted = jobLauncher.run(job, params("2026-11-02", "weekly-20261102-r2"));

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20261102-r2");
        // r2는 늦은 구매(상품 8, 7개)를 포함한 새 입력이다. r1 결과는 그대로 남는다.
        assertThat(fixtures.ranking("weekly-20261102-r2")).containsExactly(
            Map.of("product_id", 8L, "score", 8L, "rank_no", 1),
            Map.of("product_id", 7L, "score", 5L, "rank_no", 2),
            Map.of("product_id", 9L, "score", 5L, "rank_no", 3)
        );
        assertThat(fixtures.ranking("weekly-20261102-r1")).hasSize(3);
    }

    @DisplayName("이미 공개된 snapshot의 공개 단계를 다시 실행해도 ranking이 늘거나 순위가 바뀌지 않고 같은 pointer로 수렴한다 (공개 commit 뒤 metadata만 실패한 경우).")
    @Test
    void republishingSameSnapshotConverges() throws Exception {
        fixtures.assignmentWeek("2026-11-09T00:00:00Z");
        jobLauncher.run(job, params("2026-11-09", "weekly-20261109-r1"));
        Map<String, Object> before = fixtures.snapshot("weekly-20261109-r1");

        StepContribution contribution = publishAgain("2026-11-09", "weekly-20261109-r1");

        assertThat(contribution.getExitStatus()).isEqualTo(PublishWeeklyRankingTasklet.ALREADY_PUBLISHED);
        assertThat(fixtures.ranking("weekly-20261109-r1")).hasSize(3);
        assertThat(fixtures.snapshot("weekly-20261109-r1").get("result_checksum")).isEqualTo(before.get("result_checksum"));
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20261109-r1");
    }

    @DisplayName("더 새 revision이 이미 공개된 뒤 오래된 revision이 재시작으로 완료되어도 pointer를 되돌리지 않고 SUPERSEDED로 끝난다.")
    @Test
    void doesNotRollBackPointer_toOlderRevision() throws Exception {
        fixtures.assignmentWeek("2026-11-16T00:00:00Z");
        failCalculationOnceAt.set(9L);
        JobExecution olderFailed = jobLauncher.run(job, params("2026-11-16", "weekly-20261116-r1"));
        assertThat(olderFailed.getStatus()).isEqualTo(BatchStatus.FAILED);

        jobLauncher.run(job, params("2026-11-16", "weekly-20261116-r2"));
        JobExecution olderRestarted = jobLauncher.run(job, params("2026-11-16", "weekly-20261116-r1"));

        StepExecution publish = olderRestarted.getStepExecutions().stream()
            .filter(it -> it.getStepName().equals(WeeklyRankingJobConfig.PUBLISH_STEP)).findFirst().orElseThrow();
        assertThat(olderRestarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(publish.getExitStatus().getExitCode()).isEqualTo(PublishWeeklyRankingTasklet.SUPERSEDED.getExitCode());
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20261116-r2");
        assertThat(fixtures.snapshot("weekly-20261116-r1").get("status")).isEqualTo("COMPLETED");
    }

    @DisplayName("구매가 없는 주도 정상 계산이다. 빈 완성본을 COMPLETED로 공개해 '아직 공개 안 됨'과 구분된다.")
    @Test
    void publishesEmptyWeek() throws Exception {
        JobExecution execution = jobLauncher.run(job, params("2026-10-26", "weekly-20261026-r1"));

        Map<String, Object> snapshot = fixtures.snapshot("weekly-20261026-r1");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshot.get("status")).isEqualTo("COMPLETED");
        assertThat(snapshot.get("input_count")).isEqualTo(0);
        assertThat(snapshot.get("result_checksum")).isEqualTo(RankingFixtures.sha256(""));
        assertThat(fixtures.ranking("weekly-20261026-r1")).isEmpty();
        assertThat(fixtures.publications()).extracting(row -> row.get("snapshot_id")).containsExactly("weekly-20261026-r1");
    }

    private JobParameters params(String periodStart, String snapshotId) {
        return new JobParametersBuilder()
            .addString("periodStart", periodStart, true)
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();
    }

    private StepContribution publishAgain(String periodStart, String snapshotId) {
        var tasklet = new PublishWeeklyRankingTasklet(store, clock, periodStart, snapshotId);
        var contribution = new StepContribution(MetaDataInstanceFactory.createStepExecution());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> tasklet.execute(contribution, null));
        return contribution;
    }
}
