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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurchaseInputProbeRestartTest {

    private static final String SNAPSHOT_ID = "probe-1";

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

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists ranking_snapshot_input");
        jdbc.execute("drop table if exists batch_restart_probe");
        jdbc.execute("""
            create table ranking_snapshot_input (
              snapshot_id varchar(64) not null,
              order_id bigint not null,
              product_id bigint not null,
              quantity int not null,
              confirmed_at datetime(6) not null,
              primary key (snapshot_id, product_id, order_id)
            ) engine=InnoDB
            """);
        jdbc.execute("""
            create table batch_restart_probe (
              snapshot_id varchar(64) not null,
              product_id bigint not null,
              primary key (snapshot_id, product_id)
            ) engine=InnoDB
            """);
        jdbc.batchUpdate(
            "insert into ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at) "
                + "values (?, ?, ?, ?, ?)",
            List.of(
                new Object[]{SNAPSHOT_ID, 42L, 7L, 2, "2026-08-17 01:00:00"},
                new Object[]{SNAPSHOT_ID, 42L, 8L, 1, "2026-08-17 01:00:00"},
                new Object[]{SNAPSHOT_ID, 43L, 7L, 3, "2026-08-18 01:00:00"},
                new Object[]{SNAPSHOT_ID, 44L, 9L, 5, "2026-08-19 01:00:00"}
            )
        );
    }

    @DisplayName("JOB-007: 세 번째 상품 직전에 실패하면, 같은 JobInstance 의 새 실행이 저장된 위치 다음 상품부터 이어 간다.")
    @Test
    void resumesFromLastCommittedChunk() throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addString("snapshotId", SNAPSHOT_ID, true)
            .toJobParameters();

        JobExecution first = jobLauncher.run(probe(params, true), params);

        StepExecution firstStep = first.getStepExecutions().iterator().next();
        StepExecution persistedFirstStep = jobExplorer.getStepExecution(first.getId(), firstStep.getId());
        assertAll(
            () -> assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED),
            () -> assertThat(persistedFirstStep.getExecutionContext().getInt("purchaseProductProbe.read.count"))
                .isEqualTo(2),
            () -> assertThat(probedProducts()).containsExactly(7L, 8L)
        );

        JobExecution second = jobLauncher.run(probe(params, false), params);

        StepExecution secondStep = second.getStepExecutions().iterator().next();
        assertAll(
            () -> assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED),
            () -> assertThat(second.getJobInstance().getInstanceId())
                .isEqualTo(first.getJobInstance().getInstanceId()),
            () -> assertThat(second.getId()).isNotEqualTo(first.getId()),
            () -> assertThat(secondStep.getWriteCount()).isEqualTo(1),
            () -> assertThat(probedProducts()).containsExactly(7L, 8L, 9L)
        );
    }

    private Job probe(JobParameters params, boolean failOnNine) {
        String snapshot = Objects.requireNonNull(params.getString("snapshotId"));
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe")
            .saveState(true)
            .dataSource(dataSource)
            .sql("select distinct product_id from ranking_snapshot_input where snapshot_id = ? order by product_id")
            .preparedStatementSetter(ps -> ps.setString(1, snapshot))
            .rowMapper((rs, rowNum) -> rs.getLong("product_id"))
            .build();
        var step = new StepBuilder("probeProducts", jobRepository)
            .<Long, Long>chunk(2, transactionManager)
            .reader(reader)
            .processor((ItemProcessor<Long, Long>) productId -> {
                if (failOnNine && productId == 9L) {
                    throw new IllegalStateException("before third product");
                }
                return productId;
            })
            .writer(items -> {
                for (Long productId : items) {
                    jdbc.update(
                        "insert into batch_restart_probe (snapshot_id, product_id) values (?, ?)",
                        snapshot, productId
                    );
                }
            })
            .build();
        return new JobBuilder("purchaseInputProbe", jobRepository).start(step).build();
    }

    private List<Long> probedProducts() {
        return jdbc.queryForList(
            "select product_id from batch_restart_probe where snapshot_id = ? order by product_id",
            Long.class, SNAPSHOT_ID
        );
    }
}
