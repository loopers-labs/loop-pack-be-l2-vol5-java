package com.loopers.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9주차 §3-1 연결 실험. 랭킹 정답 구현이 아니라, chunk 커밋과 reader 상태(ExecutionContext)가
 * 같은 경계에서 저장되고 재실행 시 그 지점부터 이어지는지를 실제 DB(JobRepository + 업무 테이블)로 확인한다.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurchaseInputProbeRestartTest {

    private static final String SNAPSHOT_ID = "probe-1";
    private static final String READER_NAME = "purchaseProductProbe";

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final JdbcTemplate jdbcTemplate;
    private final DefaultBatchConfiguration batchConfiguration;

    @Autowired
    PurchaseInputProbeRestartTest(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        JobRepository jobRepository,
        JobLauncher jobLauncher,
        JobExplorer jobExplorer,
        JdbcTemplate jdbcTemplate,
        DefaultBatchConfiguration batchConfiguration
    ) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.jdbcTemplate = jdbcTemplate;
        this.batchConfiguration = batchConfiguration;
    }

    @DisplayName("세 상품 중 두 번째 chunk 에서 실패하면 첫 chunk 만 commit 되고, 같은 파라미터로 재실행하면 남은 한 상품만 처리한다.")
    @Test
    void restartsFromCommittedChunkBoundary() throws Exception {
        // arrange - 환경 확인용 출력
        System.out.println("[probe] PlatformTransactionManager = " + transactionManager.getClass().getName());
        Object batchDataSource = ReflectionTestUtils.getField(batchConfiguration, "dataSource");
        System.out.println("[probe] injected DataSource = " + dataSource + " / batch DataSource = " + batchDataSource
            + " / same instance = " + (batchDataSource == dataSource));
        assertThat(batchDataSource).isSameAs(dataSource);

        // arrange - probe 테이블·fixture (이 테스트 데이터만 지운다. 전체 truncate 금지)
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS batch_restart_probe (
                snapshot_id VARCHAR(64) NOT NULL,
                product_id BIGINT NOT NULL,
                PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB
            """);
        jdbcTemplate.update("delete from batch_restart_probe where snapshot_id = ?", SNAPSHOT_ID);
        jdbcTemplate.update("delete from weekly_ranking_inputs where snapshot_id = ?", SNAPSHOT_ID);
        insertInput(42L, 7L, 2, "2026-08-17T01:00:00Z");
        insertInput(42L, 8L, 1, "2026-08-17T01:00:00Z");
        insertInput(43L, 7L, 3, "2026-08-18T01:00:00Z");
        insertInput(44L, 9L, 5, "2026-08-19T01:00:00Z");

        List<String> storedConfirmedAt = jdbcTemplate.queryForList(
            "select date_format(confirmed_at, '%Y-%m-%dT%H:%i:%sZ') from weekly_ranking_inputs "
                + "where snapshot_id = ? order by order_id, product_id",
            String.class, SNAPSHOT_ID
        );
        System.out.println("[probe] stored confirmed_at (raw column) = " + storedConfirmedAt);
        assertThat(storedConfirmedAt).containsExactly(
            "2026-08-17T01:00:00Z", "2026-08-17T01:00:00Z", "2026-08-18T01:00:00Z", "2026-08-19T01:00:00Z"
        );

        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", SNAPSHOT_ID, true)
            .toJobParameters();

        // act 1 - 상품 9 에서 실패
        JobExecution first = jobLauncher.run(probe(jobRepository, transactionManager, dataSource, params, true), params);
        StepExecution firstStep = first.getStepExecutions().iterator().next();
        StepExecution firstStepFromDb = jobExplorer.getStepExecution(first.getId(), firstStep.getId());
        printExecution("first", first, firstStepFromDb);

        // assert 1
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(probeRows()).containsExactly(7L, 8L);
        assertThat(firstStepFromDb.getExecutionContext().getInt(READER_NAME + ".read.count")).isEqualTo(2);

        // act 2 - 같은 파라미터로 재실행
        JobExecution second = jobLauncher.run(probe(jobRepository, transactionManager, dataSource, params, false), params);
        StepExecution secondStep = second.getStepExecutions().iterator().next();
        StepExecution secondStepFromDb = jobExplorer.getStepExecution(second.getId(), secondStep.getId());
        printExecution("second", second, secondStepFromDb);

        // assert 2
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(probeRows()).containsExactly(7L, 8L, 9L);
        assertThat(secondStepFromDb.getReadCount()).isEqualTo(1);
        assertThat(secondStepFromDb.getWriteCount()).isEqualTo(1);
    }

    private Job probe(
        JobRepository repo,
        PlatformTransactionManager tx,
        DataSource ds,
        JobParameters params,
        boolean failOnNine
    ) {
        String snapshotId = params.getString("snapshotId");
        JdbcCursorItemReader<Long> reader = new JdbcCursorItemReaderBuilder<Long>()
            .name(READER_NAME)
            .saveState(true)
            .dataSource(ds)
            .sql("select distinct product_id from weekly_ranking_inputs where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshotId))
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();

        ItemProcessor<Long, Long> processor = id -> {
            if (failOnNine && id == 9L) {
                throw new IllegalStateException("before third product");
            }
            return id;
        };

        ItemWriter<Long> writer = chunk -> {
            for (Long productId : chunk) {
                jdbcTemplate.update(
                    "insert into batch_restart_probe (snapshot_id, product_id) values (?, ?)", snapshotId, productId
                );
            }
        };

        return new JobBuilder("purchaseInputProbe", repo)
            .start(new StepBuilder("probeProducts", repo)
                .<Long, Long>chunk(2, tx)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build())
            .build();
    }

    /** confirmed_at 은 UTC 벽시계 값(LocalDateTime)으로 넣어 드라이버의 JVM 시간대 변환을 피한다. */
    private void insertInput(Long orderId, Long productId, int quantity, String confirmedAtUtc) {
        LocalDateTime utcWallClock = ZonedDateTime.parse(confirmedAtUtc)
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
        jdbcTemplate.update(
            "insert into weekly_ranking_inputs "
                + "(snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, current_timestamp(6), current_timestamp(6))",
            SNAPSHOT_ID, orderId, productId, quantity, utcWallClock
        );
    }

    private List<Long> probeRows() {
        return jdbcTemplate.queryForList(
            "select product_id from batch_restart_probe where snapshot_id = ? order by product_id",
            Long.class, SNAPSHOT_ID
        );
    }

    private static void printExecution(String label, JobExecution job, StepExecution step) {
        System.out.println("[probe:" + label + "] jobInstanceId=" + job.getJobInstance().getId()
            + " jobExecutionId=" + job.getId() + " jobStatus=" + job.getStatus()
            + " stepExecutionId=" + step.getId() + " stepStatus=" + step.getStatus()
            + " readCount=" + step.getReadCount() + " writeCount=" + step.getWriteCount()
            + " commitCount=" + step.getCommitCount() + " rollbackCount=" + step.getRollbackCount());
        for (Map.Entry<String, Object> entry : step.getExecutionContext().entrySet()) {
            System.out.println("[probe:" + label + "] step ExecutionContext " + entry.getKey() + " = " + entry.getValue());
        }
    }
}
