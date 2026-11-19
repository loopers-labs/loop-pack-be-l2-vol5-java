package com.loopers.job.probe;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 랭킹 계산 전에 실제 MySQL JobRepository·transaction manager 로 chunk 결과와 reader 위치가 함께 commit 되는지 확인한다.
 * batch_restart_probe 는 확인용 테이블이며 제품 테이블이 아니다.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class BatchRestartProbeTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private JobExplorer jobExplorer;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        new JdbcTemplate(dataSource).execute("DROP TABLE IF EXISTS batch_restart_probe");
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품 9 처리 전에 실패하면 7·8 과 read.count=2 가 commit 되고, 같은 parameter 로 다시 실행하면 같은 instance 에서 9 만 추가한다.")
    @Test
    void resumesFromLastCommittedChunk_inSameJobInstance() {
        // arrange: §6 입력을 snapshot probe-1 로 고정하고 확인 테이블을 빈 상태로 둔다
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE batch_restart_probe (
              snapshot_id VARCHAR(64) NOT NULL,
              product_id BIGINT NOT NULL,
              PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB""");
        jdbc.update("""
            INSERT INTO weekly_ranking_snapshot_input
              (snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            VALUES ('probe-1', 42, 7, 2, '2026-08-17 01:00:00', NOW(6), NOW(6)),
                   ('probe-1', 42, 8, 1, '2026-08-17 01:00:00', NOW(6), NOW(6)),
                   ('probe-1', 43, 7, 3, '2026-08-18 01:00:00', NOW(6), NOW(6)),
                   ('probe-1', 44, 9, 5, '2026-08-19 01:00:00', NOW(6), NOW(6))""");
        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", "probe-1", true).toJobParameters();

        // act: 첫 실행은 상품 9 처리 직전에 실패한다
        JobExecution first = run(probe(params, true), params);

        // assert: 영속 ExecutionContext 를 새로 읽어 마지막 commit 위치를 확인한다
        StepExecution failedStep = first.getStepExecutions().iterator().next();
        StepExecution persisted = jobExplorer.getStepExecution(first.getId(), failedStep.getId());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(persisted.getExecutionContext().getInt("purchaseProductProbe.read.count")).isEqualTo(2);
        assertThat(probedProducts(jdbc)).containsExactly(7L, 8L);

        // act: 같은 식별 parameter 로 새 reader·step·job 객체를 만들어 다시 실행한다
        JobExecution second = run(probe(params, false), params);

        // assert
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(probedProducts(jdbc)).containsExactly(7L, 8L, 9L);
    }

    private Job probe(JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe").saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from weekly_ranking_snapshot_input "
                + "where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, n) -> rs.getLong("product_id"))
            .build();
        var jdbc = new JdbcTemplate(dataSource);
        var step = new StepBuilder("probeProducts", jobRepository)
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
                    jdbc.update("insert into batch_restart_probe (snapshot_id, product_id) values (?, ?)", snapshot, id);
                }
            })
            .build();
        return new JobBuilder("purchaseInputProbe", jobRepository).start(step).build();
    }

    private JobExecution run(Job job, JobParameters params) {
        try {
            return jobLauncher.run(job, params);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> probedProducts(JdbcTemplate jdbc) {
        return jdbc.queryForList(
            "select product_id from batch_restart_probe where snapshot_id = 'probe-1' order by product_id", Long.class);
    }
}
