package com.loopers.job.ranking;

import com.loopers.job.ranking.support.WeeklyRankingTestDatabase;

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
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class RankingJobRestartProbeTest {
    private static final String SNAPSHOT = "probe-1";

    @Autowired @Qualifier("mySqlMainDataSource") private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobLauncher jobLauncher;
    @Autowired private JobExplorer jobExplorer;

    private JdbcTemplate jdbc;

    private JobExecution first;
    private JobExecution second;
    private List<Long> productsAfterFailure;
    private StepExecution failedStep;

    @BeforeEach
    void failAtNineAndRestartWithTheSameParameters() throws Exception {
        var database = new WeeklyRankingTestDatabase(dataSource);
        database.reset();
        database.insertWeek20260817Fixture();
        jdbc = database.jdbc();
        jdbc.update("INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at) "
                + "SELECT ?, order_id, product_id, quantity, confirmed_at FROM product_purchase_activity", SNAPSHOT);
        jdbc.execute("CREATE TABLE batch_restart_probe (snapshot_id VARCHAR(64) NOT NULL, "
                + "product_id BIGINT NOT NULL, PRIMARY KEY (snapshot_id, product_id)) ENGINE=InnoDB");
        JobParameters params = new JobParametersBuilder().addString("snapshotId", SNAPSHOT, true).toJobParameters();

        first = jobLauncher.run(probe(params, true), params);
        productsAfterFailure = probedProducts();
        failedStep = persistedStep(first);
        second = jobLauncher.run(probe(params, false), params);
    }

    @Test
    @DisplayName("상품 9에서 첫 실행이 실패한다")
    void firstExecutionFails() {
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("실패 후 확인 테이블에는 상품 7과 8만 남는다")
    void firstChunkRemainsAfterFailure() {
        assertThat(productsAfterFailure).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("실패 후 체크포인트에는 읽기 위치 2가 저장된다")
    void checkpointAfterFailureIsTwo() {
        assertThat(failedStep.getExecutionContext().getInt("purchaseProductProbe.read.count")).isEqualTo(2);
    }

    @Test
    @DisplayName("재시작은 같은 JobInstance다")
    void restartUsesSameJobInstance() {
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
    }

    @Test
    @DisplayName("재시작은 새로운 JobExecution을 만든다")
    void restartCreatesNewExecution() {
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    @DisplayName("재시작은 완료된다")
    void restartCompletes() {
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("재시작은 남은 상품 9 하나만 저장한다")
    void restartWritesOneProduct() {
        assertThat(persistedStep(second).getWriteCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("재시작 후 확인 테이블에는 상품 7, 8, 9가 모두 있다")
    void allProductsRemainAfterRestart() {
        assertThat(probedProducts()).containsExactly(7L, 8L, 9L);
    }

    private Job probe(JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
                .name("purchaseProductProbe").saveState(true)
                .dataSource(dataSource)
                .sql("select distinct product_id from ranking_snapshot_input "
                        + "where snapshot_id = ? order by product_id")
                .preparedStatementSetter(ps -> ps.setString(1, snapshot))
                .rowMapper((rs, n) -> rs.getLong("product_id"))
                .build();
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
                        jdbc.update("insert into batch_restart_probe (snapshot_id, product_id) values (?, ?)",
                                snapshot, id);
                    }
                })
                .build();
        return new JobBuilder("purchaseInputProbe", jobRepository).start(step).build();
    }

    private StepExecution persistedStep(JobExecution execution) {
        StepExecution inMemory = execution.getStepExecutions().iterator().next();
        return jobExplorer.getStepExecution(execution.getId(), inMemory.getId());
    }

    private List<Long> probedProducts() {
        return jdbc.queryForList("select product_id from batch_restart_probe where snapshot_id = ? "
                + "order by product_id", Long.class, SNAPSHOT);
    }
}
