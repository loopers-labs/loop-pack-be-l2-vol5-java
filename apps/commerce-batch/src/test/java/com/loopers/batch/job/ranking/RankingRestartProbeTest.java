package com.loopers.batch.job.ranking;

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
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실습 §3 의 연결 확인. 랭킹 계산 전에 chunk 결과 · reader 위치가 실제 DB TX 로 함께 commit 되고,
 * 같은 JobInstance 의 새 JobExecution 이 그 위치부터 이어 가는지 본다. 제품 테이블이 아니라 이 테스트만의 테이블을 씀
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class RankingRestartProbeTest {

    private static final String JOB_NAME = "purchaseInputProbe";
    private static final String STEP_NAME = "probeProducts";
    private static final String READER_NAME = "purchaseProductProbe";

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    private JdbcTemplate jdbc;

    // 로컬 인프라를 재사용해도 이전 실행의 JobInstance 와 겹치지 않게 함. 두 실행에는 같은 값을 씀
    private final String snapshotId = "probe-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            create table if not exists restart_probe_input (
                snapshot_id VARCHAR(64) NOT NULL,
                order_id BIGINT NOT NULL,
                product_id BIGINT NOT NULL,
                quantity INT NOT NULL,
                confirmed_at DATETIME(6) NOT NULL,
                PRIMARY KEY (snapshot_id, order_id, product_id)
            ) ENGINE=InnoDB""");
        jdbc.execute("""
            create table if not exists batch_restart_probe (
                snapshot_id VARCHAR(64) NOT NULL,
                product_id BIGINT NOT NULL,
                PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB""");
        // 실습 §6 fixture. 주문 42 는 두 품목
        insertInput(42L, 7L, 2, "2026-08-17 01:00:00");
        insertInput(42L, 8L, 1, "2026-08-17 01:00:00");
        insertInput(43L, 7L, 3, "2026-08-18 01:00:00");
        insertInput(44L, 9L, 5, "2026-08-19 01:00:00");
    }

    @DisplayName("세 번째 상품 직전에 실패한 뒤 같은 parameter 로 다시 실행하면, 같은 JobInstance 의 새 실행이 마지막 commit 위치부터 이어 간다.")
    @Test
    void resumesFromLastCommittedChunk() throws Exception {
        // arrange
        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", snapshotId, true)
            .toJobParameters();

        // act
        JobExecution first = jobLauncher.run(probe(params, true), params);

        // assert: 첫 chunk(7, 8)만 commit 되고 reader 위치 2 가 영속됨
        StepExecution firstStep = reloadStep(first);
        assertAll(
            () -> assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(probedProducts()).containsExactly(7L, 8L),
            () -> assertThat(firstStep.getExecutionContext().getInt(READER_NAME + ".read.count")).isEqualTo(2),
            () -> assertThat(firstStep.getCommitCount()).isEqualTo(1)
        );

        // act: reader · step · job 객체를 새로 만들고 같은 parameter 로 실행
        JobExecution second = jobLauncher.run(probe(params, false), params);

        // assert: 상품 9 한 건만 새로 씀. 7 · 8 을 다시 쓰면 PK 충돌로 실패함
        StepExecution secondStep = reloadStep(second);
        assertAll(
            () -> assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId()),
            () -> assertThat(second.getId()).isNotEqualTo(first.getId()),
            () -> assertThat(secondStep.getReadCount()).isEqualTo(1),
            () -> assertThat(secondStep.getWriteCount()).isEqualTo(1),
            () -> assertThat(probedProducts()).containsExactly(7L, 8L, 9L)
        );
    }

    private Job probe(JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name(READER_NAME)
            .saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from restart_probe_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();
        var step = new StepBuilder(STEP_NAME, jobRepository)
            .<Long, Long>chunk(2, transactionManager)
            .reader(reader)
            .processor((ItemProcessor<Long, Long>) id -> {
                if (failOnNine && id == 9L) {
                    throw new IllegalStateException("before third product");
                }
                return id;
            })
            .writer(items -> items.forEach(id ->
                jdbc.update("insert into batch_restart_probe (snapshot_id, product_id) values (?, ?)", snapshot, id)))
            .build();
        return new JobBuilder(JOB_NAME, jobRepository).start(step).build();
    }

    /** 메모리의 실행 객체가 아니라 저장된 metadata 를 다시 읽음 */
    private StepExecution reloadStep(JobExecution execution) {
        StepExecution step = execution.getStepExecutions().iterator().next();
        return jobExplorer.getStepExecution(execution.getId(), step.getId());
    }

    private List<Long> probedProducts() {
        return jdbc.queryForList(
            "select product_id from batch_restart_probe where snapshot_id = ? order by product_id", Long.class, snapshotId);
    }

    private void insertInput(Long orderId, Long productId, int quantity, String confirmedAtUtc) {
        jdbc.update("insert into restart_probe_input (snapshot_id, order_id, product_id, quantity, confirmed_at) values (?, ?, ?, ?, ?)",
            snapshotId, orderId, productId, quantity, confirmedAtUtc);
    }
}
