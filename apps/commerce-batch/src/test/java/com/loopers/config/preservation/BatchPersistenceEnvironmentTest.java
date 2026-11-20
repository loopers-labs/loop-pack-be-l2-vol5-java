package com.loopers.config.preservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.SimpleJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BatchPersistenceEnvironmentTest {
    @Test
    @DisplayName("업무 행과 Batch context가 같은 연결에서 commit·rollback되고 새 컨텍스트에도 보존된다")
    void preservesBusinessAndMetadataWithRealTransactions() throws Exception {
        try (var database = new PreservedMySql()) {
            database.prepareCommerceSchema();
            prepareBatchSchema(database);
            // First preparation is explicit and must not silently accept an already initialized schema.
            assertThatThrownBy(() -> prepareBatchSchema(database)).isInstanceOf(RuntimeException.class);

            Long executionId;
            Long instanceId;
            Long stepId;
            DataSource previousDataSource;
            JobRepository previousRepository;
            JobExplorer previousExplorer;
            try (var context = start(database)) {
                var dataSource = context.getBean(DataSource.class);
                try (var connection = dataSource.getConnection()) {
                    assertThat(connection.getMetaData().getURL()).isEqualTo(database.jdbcUrl());
                }
                var repository = context.getBean(JobRepository.class);
                var explorer = context.getBean(JobExplorer.class);
                var manager = context.getBean(PlatformTransactionManager.class);
                assertThat(manager).isInstanceOf(JpaTransactionManager.class);
                assertThat(((JpaTransactionManager) manager).getDataSource()).isSameAs(dataSource);
                assertThat(dataSource.unwrap(HikariDataSource.class)).isNotNull();
                assertThat(AopTestUtils.<Object>getUltimateTargetObject(repository)).isInstanceOf(SimpleJobRepository.class);
                assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("ranking-preserve");

                var execution = repository.createJobExecution("c01PersistenceProbe",
                    new JobParametersBuilder().addString("fixture", "c01").toJobParameters());
                var step = new StepExecution("c01TransactionProbe", execution);
                repository.add(step);
                step.getExecutionContext().putInt("probe.position", 0);
                repository.updateExecutionContext(step);
                execution.getExecutionContext().putString("fixture", "preserved");
                repository.updateExecutionContext(execution);
                executionId = execution.getId();
                instanceId = execution.getJobInstance().getId();
                stepId = step.getId();

                var observation = context.getBean(ConnectionObservation.class);
                var jdbc = new JdbcTemplate(dataSource);
                var transactions = new TransactionTemplate(manager);
                observation.clear();
                transactions.executeWithoutResult(status -> {
                    insertActivity(jdbc, 42, 7, 2);
                    step.getExecutionContext().putInt("probe.position", 1);
                    repository.updateExecutionContext(step);
                });
                assertSameConnection(observation, "commit");
                assertThat(explorer.getStepExecution(executionId, stepId).getExecutionContext().getInt("probe.position"))
                    .isEqualTo(1);
                assertCommittedActivity(database);

                observation.clear();
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    insertActivity(jdbc, 43, 8, 99);
                    step.getExecutionContext().putInt("probe.position", 2);
                    repository.updateExecutionContext(step);
                    throw new IllegalStateException("c01 rollback injection");
                })).isInstanceOf(IllegalStateException.class).hasMessage("c01 rollback injection");
                assertSameConnection(observation, "rollback");
                // Re-read persistence, not the mutated in-memory StepExecution.
                assertThat(explorer.getStepExecution(executionId, stepId).getExecutionContext().getInt("probe.position"))
                    .isEqualTo(1);
                assertCommittedActivity(database);
                previousDataSource = dataSource;
                previousRepository = repository;
                previousExplorer = explorer;
                System.out.printf("C01 instance=%d execution=%d step=%d committedPosition=1 rollbackPosition=1 TM=%s%n",
                    instanceId, executionId, stepId, manager.getClass().getName());
            }

            assertThat(previousDataSource.unwrap(HikariDataSource.class).isClosed()).isTrue();
            try (var restarted = start(database)) {
                assertThat(restarted.getBean(DataSource.class)).isNotSameAs(previousDataSource);
                assertThat(restarted.getBean(JobRepository.class)).isNotSameAs(previousRepository);
                var explorer = restarted.getBean(JobExplorer.class);
                assertThat(explorer).isNotSameAs(previousExplorer);
                var execution = explorer.getJobExecution(executionId);
                assertThat(execution.getJobInstance().getId()).isEqualTo(instanceId);
                assertThat(execution.getExecutionContext().getString("fixture")).isEqualTo("preserved");
                assertThat(explorer.getStepExecution(executionId, stepId).getExecutionContext().getInt("probe.position"))
                    .isEqualTo(1);
                assertCommittedActivity(database);
                System.out.printf("C01 recreated-context execution=%d step=%d preservedPosition=1%n", executionId, stepId);
            }
        }
    }

    @Test
    @DisplayName("metadata 미준비 시 명시 launch가 실패하고 tasklet 실행이나 schema 자동 생성이 없다")
    void failsLaunchWithoutMetadataSchema() throws Exception {
        try (var database = new PreservedMySql(); var context = start(database)) {
            var invoked = new AtomicBoolean();
            var repository = context.getBean(JobRepository.class);
            var step = new StepBuilder("c01MissingSchemaStep", repository)
                .tasklet((contribution, chunkContext) -> {
                    invoked.set(true);
                    return RepeatStatus.FINISHED;
                }, context.getBean(PlatformTransactionManager.class)).build();
            var job = new JobBuilder("c01MissingSchemaJob", repository).start(step).build();

            assertThatThrownBy(() -> context.getBean(JobLauncher.class).run(job,
                new JobParametersBuilder().addString("fixture", "missing-schema").toJobParameters()))
                .hasRootCauseInstanceOf(SQLSyntaxErrorException.class);
            assertThat(invoked).isFalse();
            try (var connection = database.connect(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema='loopers' AND table_name LIKE 'BATCH_%'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    private ConfigurableApplicationContext start(PreservedMySql database) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("inheritedFixtureEnvironment",
            Map.of("DATASOURCE_MYSQL_JPA_MAIN_JDBC_URL", "jdbc:mysql://127.0.0.1:1/shared")));
        return PreservationContexts.start(BatchConfiguration.class, environment, database.arguments());
    }

    private void prepareBatchSchema(PreservedMySql database) throws SQLException {
        var resource = new ClassPathResource("preservation/001-batch-metadata-mysql.sql");
        assertThat(resource.exists()).as("deliverable explicit Batch metadata SQL").isTrue();
        try (var connection = database.connect()) {
            ScriptUtils.executeSqlScript(connection, resource);
        }
    }

    private void insertActivity(JdbcTemplate jdbc, long orderId, long productId, int quantity) {
        jdbc.update("""
            INSERT INTO order_confirmation_inbox
              (id, event_id, order_id, event_type, schema_version, confirmed_at, normalized_payload, processed_at)
            VALUES (?, ?, ?, 'OrderConfirmed', 1, '2026-08-17 01:00:00.123456', '{}', '2026-08-17 02:00:00')
            """, orderId, new UUID(0, orderId).toString(), orderId);
        jdbc.update("""
            INSERT INTO product_purchase_activities (inbox_id, order_id, product_id, quantity, confirmed_at)
            VALUES (?, ?, ?, ?, '2026-08-17 01:00:00.123456')
            """, orderId, orderId, productId, quantity);
    }

    private void assertCommittedActivity(PreservedMySql database) throws SQLException {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT order_id, product_id, quantity, confirmed_at "
                + "FROM product_purchase_activities ORDER BY order_id")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(42);
                assertThat(result.getLong(2)).isEqualTo(7);
                assertThat(result.getInt(3)).isEqualTo(2);
                assertThat(result.getString(4)).isEqualTo("2026-08-17 01:00:00.123456");
                assertThat(result.next()).isFalse();
            }
            try (var result = statement.executeQuery("SELECT order_id FROM order_confirmation_inbox ORDER BY order_id")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(42);
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertSameConnection(ConnectionObservation observation, String completion) {
        var writes = observation.events().stream().filter(event -> event.operation().equals("sql")
            && (event.sql().contains("INSERT INTO product_purchase_activities")
                || event.sql().contains("UPDATE BATCH_STEP_EXECUTION_CONTEXT"))).toList();
        assertThat(writes).hasSize(2);
        assertThat(writes).extracting(ConnectionObservation.Event::connectionId).containsOnly(writes.getFirst().connectionId());
        assertThat(writes).allMatch(event -> !event.autoCommit());
        assertThat(observation.events()).anyMatch(event -> event.operation().equals(completion)
            && event.connectionId() == writes.getFirst().connectionId());
        System.out.printf("C01 TX %s connection=%d business+metadata autoCommit=false%n", completion,
            writes.getFirst().connectionId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan("com.loopers.config.jpa")
    static class BatchConfiguration {
        @Bean
        static ConnectionObservation connectionObservation() {
            return new ConnectionObservation();
        }
    }
}
