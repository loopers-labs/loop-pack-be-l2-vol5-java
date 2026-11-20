package com.loopers.job.ranking;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과제 §3의 연결 예시. 고정한 입력에서 상품 7·8·9를 chunk=2로 읽고,
 * 세 번째 상품 직전에 실패한 실행을 같은 JobInstance로 이어 가는지 실제 DB metadata로 확인한다.
 * 랭킹 점수·순위·공개의 구현이 아니다.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurchaseInputRestartProbeTest {

    private static final String JOB_NAME = "purchaseInputProbe";
    private static final String STEP_NAME = "probeProducts";
    private static final String READER_NAME = "purchaseProductProbe";
    private static final String SNAPSHOT_ID = "probe-1";

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("mySqlMainDataSource")
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void freezeProbeInput() {
        jdbc = new JdbcTemplate(dataSource);
        // ranking_snapshot_input은 실제 스키마(schema/weekly-ranking.sql)를 쓴다. 확인 테이블만 테스트가 만든다.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS batch_restart_probe (
              snapshot_id VARCHAR(64) NOT NULL,
              product_id BIGINT NOT NULL,
              PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB
            """);

        // 과제 §6의 구매 활동 4행. 주문 42는 상품 두 개를 함께 샀다.
        jdbc.update("DELETE FROM ranking_snapshot_input WHERE snapshot_id = ?", SNAPSHOT_ID);
        jdbc.update("DELETE FROM batch_restart_probe WHERE snapshot_id = ?", SNAPSHOT_ID);
        jdbc.batchUpdate(
            "INSERT INTO ranking_snapshot_input VALUES (?, ?, ?, ?, ?)",
            List.of(
                new Object[] {SNAPSHOT_ID, 42L, 7L, 2, "2026-08-17 01:00:00"},
                new Object[] {SNAPSHOT_ID, 42L, 8L, 1, "2026-08-17 01:00:00"},
                new Object[] {SNAPSHOT_ID, 43L, 7L, 3, "2026-08-18 01:00:00"},
                new Object[] {SNAPSHOT_ID, 44L, 9L, 5, "2026-08-19 01:00:00"}
            )
        );
    }

    @DisplayName("세 번째 상품 직전에 실패한 실행을 같은 parameter로 다시 실행하면 같은 JobInstance에서 상품 9만 이어서 쓴다.")
    @Test
    void restartsFromLastCommittedChunk() throws Exception {
        // writer·repository가 같은 DataSource의 트랜잭션 관리자를 쓰는지 먼저 확인한다.
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);

        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", SNAPSHOT_ID, true)
            .toJobParameters();
        // metadata를 지우지 않으므로, 이전 실행이 남아 있으면 이 실험은 성립하지 않는다.
        assertThat(jobExplorer.getJobInstance(JOB_NAME, params)).isNull();

        // 첫 실행: 상품 9 처리 직전에 실패
        JobExecution first = jobLauncher.run(probe(params, true), params);

        StepExecution firstStep = reloadStep(first);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(firstStep.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(firstStep.getCommitCount()).isEqualTo(1);
        assertThat(firstStep.getExecutionContext().getInt(READER_NAME + ".read.count")).isEqualTo(2);
        assertThat(probeRows()).containsExactly(7L, 8L);

        // 재실행: 같은 식별 parameter, 같은 DB metadata, 새로 만든 reader·step·job 객체
        JobExecution second = jobLauncher.run(probe(params, false), params);

        StepExecution secondStep = reloadStep(second);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(secondStep.getReadCount()).isEqualTo(1);
        assertThat(secondStep.getWriteCount()).isEqualTo(1);
        assertThat(probeRows()).containsExactly(7L, 8L, 9L);
        assertThat(jobExplorer.getJobExecutions(first.getJobInstance())).hasSize(2);
    }

    private Job probe(JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name(READER_NAME)
            .saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input "
                + "where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, n) -> rs.getLong("product_id"))
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
            .writer(items -> {
                for (Long id : items) {
                    jdbc.update("insert into batch_restart_probe "
                        + "(snapshot_id, product_id) values (?, ?)", snapshot, id);
                }
            })
            .build();
        return new JobBuilder(JOB_NAME, jobRepository).start(step).build();
    }

    /** 메모리의 실행 객체가 아니라 JobRepository에 저장된 StepExecution을 다시 읽는다. */
    private StepExecution reloadStep(JobExecution execution) {
        StepExecution inMemory = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(STEP_NAME))
            .findFirst()
            .orElseThrow();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }

    /** 배치와 별도 연결로 commit된 확인 행만 읽는다. */
    private List<Long> probeRows() {
        return jdbc.queryForList(
            "SELECT product_id FROM batch_restart_probe WHERE snapshot_id = ? ORDER BY product_id",
            Long.class,
            SNAPSHOT_ID
        );
    }
}
