package com.loopers.job.ranking;

import com.loopers.application.ranking.WeeklyRankingSnapshotService;
import com.loopers.domain.ranking.RankingSnapshotStore;
import com.loopers.domain.ranking.WeeklyRankingKey;
import com.loopers.infrastructure.ranking.JdbcRankingSnapshotStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A test-only, unique INSERT writer makes accidental replay of committed products fail visibly. */
@SpringJUnitConfig(WeeklyRankingCheckpointProbeIntegrationTest.Config.class)
@TestPropertySource(properties = {"spring.batch.job.enabled=false", "spring.batch.jdbc.initialize-schema=never"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class WeeklyRankingCheckpointProbeIntegrationTest {
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 17);
    private static final String READER_KEY = "purchaseProductProbe.read.count";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_checkpoint_probe_test");

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JobRepository repository;
    @Autowired private JobExplorer explorer;
    @Autowired private JobLauncher launcher;
    @Autowired private WeeklyRankingSnapshotService snapshots;

    @Test
    void freshReaderAndExecutionResumeOnlyTheThirdProductUsingPersistedCheckpoint() throws Exception {
        seedOriginalPurchases();
        var key = new WeeklyRankingKey(PERIOD_START, 1);
        var frozen = snapshots.freeze(key);
        var originalInputs = jdbc.queryForList("SELECT * FROM weekly_ranking_input ORDER BY order_id, product_id");
        var originalManifest = jdbc.queryForList("SELECT * FROM weekly_ranking_snapshot");
        assertThat(frozen.inputCount()).isEqualTo(4);
        assertThat(frozen.productCount()).isEqualTo(3);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var parameters = new JobParametersBuilder().addString("snapshotId", key.snapshotId(), true).toJobParameters();
        List<Long> firstProcessed = new ArrayList<>();
        List<Long> resumedProcessed = new ArrayList<>();

        JobExecution first = launcher.run(probe(parameters, true, firstProcessed), parameters);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(firstProcessed).containsExactly(7L, 8L, 9L);
        assertThat(products()).containsExactly(7L, 8L);
        var firstStep = first.getStepExecutions().iterator().next();
        var storedFirstStep = explorer.getStepExecution(first.getId(), firstStep.getId());
        assertThat(storedFirstStep).isNotNull();
        assertThat(storedFirstStep.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(storedFirstStep.getExecutionContext().getInt(READER_KEY)).isEqualTo(2);
        assertThat(storedFirstStep.getWriteCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class)).isEqualTo(1L);

        // New reader, step and job objects; the same database, frozen input and identifying parameters survive.
        JobExecution resumed = launcher.run(probe(parameters, false, resumedProcessed), parameters);

        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resumed.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(resumed.getId()).isNotEqualTo(first.getId());
        assertThat(resumedProcessed).containsExactly(9L);
        assertThat(products()).containsExactly(7L, 8L, 9L);
        var resumedStep = resumed.getStepExecutions().iterator().next();
        var storedResumedStep = explorer.getStepExecution(resumed.getId(), resumedStep.getId());
        assertThat(storedResumedStep).isNotNull();
        assertThat(storedResumedStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(storedResumedStep.getWriteCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT * FROM weekly_ranking_input ORDER BY order_id, product_id")).isEqualTo(originalInputs);
        assertThat(jdbc.queryForList("SELECT * FROM weekly_ranking_snapshot")).isEqualTo(originalManifest);
    }

    private Job probe(JobParameters parameters, boolean failOnNine, List<Long> processed) {
        String snapshotId = parameters.getString("snapshotId");
        var reader = new JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe")
            .saveState(true)
            .dataSource(dataSource)
            .sql("SELECT DISTINCT product_id FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY product_id")
            .preparedStatementSetter(statement -> statement.setString(1, snapshotId))
            .rowMapper((row, index) -> row.getLong("product_id"))
            .build();
        var step = new StepBuilder("probeProducts", repository)
            .<Long, Long>chunk(2, transactionManager)
            .reader(reader)
            .processor((ItemProcessor<Long, Long>) productId -> {
                processed.add(productId);
                if (failOnNine && productId == 9L) {
                    throw new IllegalStateException("before third product");
                }
                return productId;
            })
            .writer(items -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                for (Long productId : items) {
                    jdbc.update("INSERT INTO batch_restart_probe (snapshot_id, product_id) VALUES (?, ?)", snapshotId, productId);
                }
            })
            .build();
        return new JobBuilder("purchaseInputProbe", repository).start(step).build();
    }

    private List<Long> products() {
        return jdbc.query("SELECT product_id FROM batch_restart_probe ORDER BY product_id", (row, index) -> row.getLong(1));
    }

    private void seedOriginalPurchases() {
        jdbc.execute("""
            CREATE TABLE batch_restart_probe (
                snapshot_id VARCHAR(64) NOT NULL,
                product_id BIGINT NOT NULL,
                PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB
            """);
        long[] orders = {42, 43, 44};
        String[] times = {"2026-08-17T01:00:00Z", "2026-08-18T01:00:00Z", "2026-08-19T01:00:00Z"};
        for (int index = 0; index < orders.length; index++) {
            jdbc.update("""
                INSERT INTO processed_order_confirmation
                    (event_id, order_id, event_type, schema_version, confirmed_at_second, confirmed_at_nano)
                VALUES (?, ?, 'OrderConfirmed', 1, ?, 0)
                """, "probe-order-" + orders[index], orders[index], Instant.parse(times[index]).getEpochSecond());
        }
        long[][] facts = {{42, 7, 2, 0}, {42, 8, 1, 0}, {43, 7, 3, 1}, {44, 9, 5, 2}};
        for (long[] fact : facts) {
            jdbc.update("""
                INSERT INTO product_purchase_activity
                    (order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano) VALUES (?, ?, ?, ?, 0)
                """, fact[0], fact[1], fact[2], Instant.parse(times[(int) fact[3]]).getEpochSecond());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(BatchAutoConfiguration.class)
    @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(MYSQL.getJdbcUrl());
            source.setUsername(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            source.setMaximumPoolSize(5);
            source.setMinimumIdle(0);
            source.setConnectionTimeout(5000);
            new ResourceDatabasePopulator(
                new ClassPathResource("fixtures/purchase/001-purchase-activity.sql"),
                new ClassPathResource("db/schema/001-weekly-ranking-input.sql"),
                new ClassPathResource("org/springframework/batch/core/schema-mysql.sql")
            ).execute(source);
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        JdbcTransactionManager transactionManager(DataSource source) {
            return new JdbcTransactionManager(source);
        }

        @Bean
        RankingSnapshotStore snapshotStore(JdbcTemplate template) {
            return new JdbcRankingSnapshotStore(template);
        }

        @Bean
        WeeklyRankingSnapshotService snapshotService(RankingSnapshotStore store) {
            return new WeeklyRankingSnapshotService(store, Clock.fixed(Instant.parse("2026-08-24T00:30:00Z"), ZoneOffset.UTC), 2);
        }
    }
}
