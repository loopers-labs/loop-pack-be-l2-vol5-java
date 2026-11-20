package com.loopers.job.ranking;

import com.loopers.application.ranking.WeeklyRankingSnapshotService;
import com.loopers.application.ranking.WeeklyRankingPublicationService;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.RankingPurchase;
import com.loopers.domain.ranking.RankingScoreStore;
import com.loopers.domain.ranking.RankingSnapshotStore;
import com.loopers.domain.ranking.WeeklyRankingKey;
import com.loopers.infrastructure.ranking.JdbcRankingScoreStore;
import com.loopers.infrastructure.ranking.JdbcRankingSnapshotStore;
import com.loopers.infrastructure.ranking.JdbcRankingPublicationStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** Boots production Job wiring on an owned database; contexts close without recreating its tables. */
@Testcontainers
@Timeout(90)
class WeeklyRankingJobIntegrationTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
    private static final String READER_POSITION = "weeklyRankingProducts.read.count";
    private static final String INPUT_CHECKSUM = "4a00f7d7a6421c8e457a59012754f53ca6069dc391e14c582fc7e551bab60863";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_weekly_job_owned");

    @BeforeAll
    static void initializeOwnedSchemaExactlyOnce() {
        try (HikariDataSource source = newPool()) {
            new ResourceDatabasePopulator(
                new ClassPathResource("fixtures/purchase/001-purchase-activity.sql"),
                new ClassPathResource("db/schema/001-weekly-ranking-input.sql"),
                new ClassPathResource("db/schema/002-weekly-ranking-score.sql"),
                new ClassPathResource("db/schema/003-weekly-ranking-publication.sql"),
                new ClassPathResource("org/springframework/batch/core/schema-mysql.sql")
            ).execute(source);
        }
    }

    @BeforeEach
    void removeOnlyPreviousTestFactsWithAllContextsClosed() {
        try (HikariDataSource source = newPool()) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            for (String table : List.of("BATCH_STEP_EXECUTION_CONTEXT", "BATCH_JOB_EXECUTION_CONTEXT",
                "BATCH_STEP_EXECUTION", "BATCH_JOB_EXECUTION_PARAMS", "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE",
                "weekly_ranking_publication", "weekly_ranking_result", "weekly_ranking_score", "weekly_ranking_input", "weekly_ranking_snapshot",
                "product_purchase_activity", "processed_order_confirmation")) {
                jdbc.update("DELETE FROM " + table);
            }
        }
    }

    @Test
    void newContextRestartsSameInstanceAtProductNineUsingFrozenInputAndPersistentCheckpoint() throws Exception {
        WeeklyRankingKey normalKey = new WeeklyRankingKey(WEEK, 1);
        WeeklyRankingKey restartKey = new WeeklyRankingKey(WEEK, 2);
        long firstInstance;
        long firstExecution;
        long firstStep;
        List<Map<String, Object>> frozenRows;
        List<Map<String, Object>> normalScores;
        try (ConfigurableApplicationContext first = open()) {
            assertJpaAndDataSourceWiring(first);
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            seedFourPurchases(jdbc);
            JobExecution normal = launch(first, normalKey);
            assertThat(normal.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertPublished(jdbc, normalKey);
            normalScores = scores(jdbc, normalKey);
            assertExpectedScores(normalScores);

            RankingScoreStore store = first.getBean(RankingScoreStore.class);
            doAnswer(invocation -> {
                if (invocation.<Long>getArgument(1) == 9L) {
                    throw new InjectedFailure("Before product 9");
                }
                return invocation.callRealMethod();
            }).when(store).aggregate(eq(restartKey.snapshotId()), anyLong());

            JobExecution failed = launch(first, restartKey);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertPublished(jdbc, normalKey);
            assertThat(scores(jdbc, restartKey)).containsExactly(
                Map.of("product_id", 7L, "score", 5L), Map.of("product_id", 8L, "score", 1L));
            assertThat(checkpoint(first, failed)).isEqualTo(2);
            firstInstance = failed.getJobInstance().getInstanceId();
            firstExecution = failed.getId();
            firstStep = scoreStep(failed).getId();
            frozenRows = inputs(jdbc, restartKey);
            assertThat(frozenRows).hasSize(4);
            assertThat(jdbc.queryForObject("SELECT input_checksum FROM weekly_ranking_snapshot WHERE snapshot_id=?",
                String.class, restartKey.snapshotId())).isEqualTo(INPUT_CHECKSUM);
            seedPurchase(jdbc, 45, 7, 4, Instant.parse("2026-08-20T01:00:00.000000001Z"));
            assertThat(first.getBean(SqlObservation.class).pairs).isNotEmpty().allSatisfy(pair ->
                assertThat(pair.writerConnection()).isEqualTo(pair.checkpointConnection()));
        }

        try (ConfigurableApplicationContext restarted = open()) {
            assertJpaAndDataSourceWiring(restarted);
            List<Long> visited = new ArrayList<>();
            RankingScoreStore store = restarted.getBean(RankingScoreStore.class);
            doAnswer(invocation -> {
                visited.add(invocation.getArgument(1));
                return invocation.callRealMethod();
            }).when(store).aggregate(eq(restartKey.snapshotId()), anyLong());
            JobExecution completed = launch(restarted, restartKey);

            assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(completed.getJobInstance().getInstanceId()).isEqualTo(firstInstance);
            assertThat(completed.getId()).isNotEqualTo(firstExecution);
            assertThat(completed.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("calculateWeeklyScores", "publishWeeklyRanking");
            assertThat(visited).containsExactly(9L);
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertThat(scores(jdbc, restartKey)).isEqualTo(normalScores);
            assertPublished(jdbc, restartKey);
            assertThat(results(jdbc, restartKey)).isEqualTo(results(jdbc, normalKey));
            assertThat(jdbc.queryForObject("SELECT result_checksum FROM weekly_ranking_snapshot WHERE snapshot_id=?",
                String.class, restartKey.snapshotId())).isEqualTo(jdbc.queryForObject(
                    "SELECT result_checksum FROM weekly_ranking_snapshot WHERE snapshot_id=?", String.class, normalKey.snapshotId()));
            assertThat(inputs(jdbc, restartKey)).isEqualTo(frozenRows);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class)).isEqualTo(5L);
            verify(restarted.getBean(RankingSnapshotStore.class), never())
                .readSourcePage(any(), nullable(RankingPurchase.class), anyInt());
            JobExecution stored = restarted.getBean(JobExplorer.class).getJobExecution(completed.getId());
            assertThat(stored).isNotNull();
            assertThat(stored.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(restarted.getBean(SqlObservation.class).pairs).hasSize(1).allSatisfy(pair ->
                assertThat(pair.writerConnection()).isEqualTo(pair.checkpointConnection()));
            org.slf4j.LoggerFactory.getLogger(WeeklyRankingJobIntegrationTest.class).info(
                "W9 restart snapshot={} instance={} firstExecution={} resumedExecution={} firstStep={} resumedStep={}"
                    + " storedPosition=2 resumedProducts={} sameConnectionPairs={}",
                restartKey.snapshotId(), firstInstance, firstExecution, completed.getId(), firstStep, scoreStep(completed).getId(), visited,
                restarted.getBean(SqlObservation.class).pairs);
        }
    }

    @ParameterizedTest
    @EnumSource(FailureBoundary.class)
    void actualScoreAndCheckpointSqlRollBackTogetherAndRestartPreservesFirstChunk(FailureBoundary boundary) throws Exception {
        WeeklyRankingKey key = new WeeklyRankingKey(WEEK, 1);
        long instanceId;
        long executionId;
        try (ConfigurableApplicationContext context = open()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            seedFourPurchases(jdbc);
            RankingScoreStore store = context.getBean(RankingScoreStore.class);
            SqlObservation observation = context.getBean(SqlObservation.class);
            doAnswer(invocation -> {
                invocation.callRealMethod();
                List<ProductScore> values = invocation.getArgument(1);
                if (values.stream().anyMatch(score -> score.productId() == 9L)) {
                    assertThat(jdbc.queryForObject("SELECT score FROM weekly_ranking_score WHERE snapshot_id=? AND product_id=9",
                        Long.class, key.snapshotId())).isEqualTo(5L);
                    observation.actualNineWrite = true;
                    observation.nineWriterConnection = observation.pendingWriter;
                    assertThat(observation.nineWriterConnection).isNotNull();
                    if (boundary == FailureBoundary.AFTER_WRITER_SQL) {
                        throw new InjectedFailure("After real score INSERT, before checkpoint");
                    }
                    if (boundary == FailureBoundary.AFTER_WRITER_CHECKED_SQL) {
                        observation.checkedAfterNine = true;
                        return null;
                    }
                    observation.failNextCheckpoint = true;
                }
                return null;
            }).when(store).writeScores(eq(key.snapshotId()), anyList());

            JobExecution failed = launch(context, key);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            if (boundary == FailureBoundary.AFTER_WRITER_CHECKED_SQL) {
                assertThat(observation.checkedFailureDelivered).isTrue();
                assertThat(failed.getAllFailureExceptions()).anyMatch(IOException.class::isInstance);
            }
            assertThat(observation.actualNineWrite).isTrue();
            assertThat(observation.rolledBackConnections).contains(observation.nineWriterConnection);
            assertThat(observation.failedAfterCheckpointSql).isEqualTo(boundary == FailureBoundary.AFTER_CHECKPOINT_SQL);
            assertThat(scores(jdbc, key)).containsExactly(
                Map.of("product_id", 7L, "score", 5L), Map.of("product_id", 8L, "score", 1L));
            assertThat(checkpoint(context, failed)).isEqualTo(2);
            assertThat(inputs(jdbc, key)).hasSize(4);
            assertThat(observation.pairs).isNotEmpty().allSatisfy(pair ->
                assertThat(pair.writerConnection()).isEqualTo(pair.checkpointConnection()));
            assertThat(observation.pairs).hasSize(boundary == FailureBoundary.AFTER_CHECKPOINT_SQL ? 2 : 1);
            instanceId = failed.getJobInstance().getInstanceId();
            executionId = failed.getId();
            org.slf4j.LoggerFactory.getLogger(WeeklyRankingJobIntegrationTest.class).info(
                "W9 rollback boundary={} snapshot={} instance={} execution={} actualNineWriterConnection={}"
                    + " rolledBackConnections={} persistedPosition=2 preservedProducts=[7,8] sameConnectionPairs={}",
                boundary, key.snapshotId(), instanceId, executionId, observation.nineWriterConnection,
                observation.rolledBackConnections, observation.pairs);
        }

        try (ConfigurableApplicationContext context = open()) {
            List<Long> visited = new ArrayList<>();
            doAnswer(invocation -> {
                visited.add(invocation.getArgument(1));
                return invocation.callRealMethod();
            }).when(context.getBean(RankingScoreStore.class)).aggregate(eq(key.snapshotId()), anyLong());
            JobExecution completed = launch(context, key);
            assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(completed.getJobInstance().getInstanceId()).isEqualTo(instanceId);
            assertThat(completed.getId()).isNotEqualTo(executionId);
            assertThat(visited).containsExactly(9L);
            assertExpectedScores(scores(context.getBean(JdbcTemplate.class), key));
        }
    }

    @Test
    void publicationCommitSurvivesFailureBeforeFinalBatchStatusAndSameInstanceRestartsOnlyPublication() throws Exception {
        WeeklyRankingKey oldKey = new WeeklyRankingKey(WEEK, 1);
        WeeklyRankingKey key = new WeeklyRankingKey(WEEK, 2);
        long instanceId;
        long executionId;
        List<Map<String, Object>> committedResults;
        String checksum;
        try (ConfigurableApplicationContext context = open()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            seedFourPurchases(jdbc);
            assertThat(launch(context, oldKey).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            context.getBean(SqlObservation.class).failAfterPublicationCommit = true;
            JobExecution failed = launch(context, key);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(context.getBean(SqlObservation.class).observedCommittedPublication).isTrue();
            assertPublished(jdbc, key);
            committedResults = results(jdbc, key);
            checksum = jdbc.queryForObject("SELECT result_checksum FROM weekly_ranking_snapshot WHERE snapshot_id=?",
                String.class, key.snapshotId());
            instanceId = failed.getJobInstance().getInstanceId();
            executionId = failed.getId();
        }
        try (ConfigurableApplicationContext restarted = open()) {
            JobExecution completed = launch(restarted, key);
            assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(completed.getJobInstance().getInstanceId()).isEqualTo(instanceId);
            assertThat(completed.getId()).isNotEqualTo(executionId);
            assertThat(completed.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("publishWeeklyRanking");
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertPublished(jdbc, key);
            assertThat(results(jdbc, key)).isEqualTo(committedResults);
            assertThat(jdbc.queryForObject("SELECT result_checksum FROM weekly_ranking_snapshot WHERE snapshot_id=?",
                String.class, key.snapshotId())).isEqualTo(checksum);
            verify(restarted.getBean(RankingSnapshotStore.class), never())
                .readSourcePage(any(), nullable(RankingPurchase.class), anyInt());
            verify(restarted.getBean(RankingScoreStore.class), never()).aggregate(any(), anyLong());
            org.slf4j.LoggerFactory.getLogger(WeeklyRankingJobIntegrationTest.class).info(
                "W9 publication gap snapshot={} instance={} failedExecution={} resumedExecution={} checksum={} rows={}",
                key.snapshotId(), instanceId, executionId, completed.getId(), checksum, committedResults);
        }
    }

    @Test
    void emptyInputCompletesWithoutScoreRowsAndCompletedInstanceCannotBeRelaunched() throws Exception {
        WeeklyRankingKey key = new WeeklyRankingKey(WEEK, 1);
        try (ConfigurableApplicationContext context = open()) {
            JobExecution completed = launch(context, key);
            assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(completed.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("freezeWeeklyInput", "calculateWeeklyScores", "publishWeeklyRanking");
            assertThat(scores(jdbc, key)).isEmpty();
            assertThat(inputs(jdbc, key)).isEmpty();
            assertPublished(jdbc, key);
            assertThat(results(jdbc, key)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT input_count FROM weekly_ranking_snapshot WHERE snapshot_id=?",
                Long.class, key.snapshotId())).isZero();
            assertThatThrownBy(() -> launch(context, key)).isInstanceOf(JobInstanceAlreadyCompleteException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class)).isEqualTo(1L);
        }
    }

    @Test
    void invalidOrChangingIdentityIsRejectedBeforeCreatingAnyJobInstance() {
        WeeklyRankingKey key = new WeeklyRankingKey(WEEK, 1);
        List<JobParameters> invalid = List.of(
            new JobParameters(),
            new JobParametersBuilder(parameters(key)).addString("periodStart", "2026-08-18").toJobParameters(),
            new JobParametersBuilder(parameters(key)).addLong("revision", 0L).toJobParameters(),
            new JobParametersBuilder(parameters(key)).addString("revision", "1").toJobParameters(),
            new JobParametersBuilder(parameters(key)).addString("snapshotId", "different-snapshot").toJobParameters(),
            new JobParametersBuilder(parameters(key)).addLong("run.id", 2L).toJobParameters(),
            new JobParametersBuilder(parameters(key)).addString("periodStart", WEEK.toString(), false).toJobParameters(),
            new JobParametersBuilder(parameters(key)).addLong("revision", 1L, false).toJobParameters(),
            new JobParametersBuilder(parameters(key)).addString("snapshotId", key.snapshotId(), false).toJobParameters()
        );
        try (ConfigurableApplicationContext context = open()) {
            for (JobParameters parameters : invalid) {
                assertThatThrownBy(() -> context.getBean(JobLauncher.class).run(
                    context.getBean("weeklyRanking", Job.class), parameters)).isInstanceOf(JobParametersInvalidException.class);
            }
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshot", Long.class)).isZero();
        }
    }

    private static ConfigurableApplicationContext open() {
        return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.config.name=w9-owned-job-test-no-external-config",
            "--spring.profiles.active=w9-owned-job-test",
            "--spring.batch.job.enabled=false", "--spring.batch.job.name=weeklyRanking",
            "--spring.batch.jdbc.initialize-schema=never", "--spring.jpa.hibernate.ddl-auto=none",
            "--spring.data.jpa.repositories.enabled=false", "--spring.data.redis.repositories.enabled=false",
            "--commerce.ranking.chunk-size=2", "--commerce.ranking.snapshot.page-size=2",
            "--logging.level.root=WARN", "--logging.level.com.loopers.job.ranking=INFO", "--spring.main.banner-mode=off");
    }

    private static HikariDataSource newPool() {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        source.setMaximumPoolSize(6);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(5000);
        source.setInitializationFailTimeout(0);
        return source;
    }

    private static JobParameters parameters(WeeklyRankingKey key) {
        return new JobParametersBuilder().addString("periodStart", key.periodStart().toString())
            .addLong("revision", (long) key.revision()).addString("snapshotId", key.snapshotId()).toJobParameters();
    }

    private static JobExecution launch(ConfigurableApplicationContext context, WeeklyRankingKey key) throws Exception {
        return context.getBean(JobLauncher.class).run(context.getBean("weeklyRanking", Job.class), parameters(key));
    }

    private static void assertJpaAndDataSourceWiring(ConfigurableApplicationContext context) {
        PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) manager).getDataSource()).isSameAs(context.getBean("mySqlMainDataSource"));
        assertThat(context.getBean(JdbcTemplate.class).getDataSource()).isSameAs(context.getBean("mySqlMainDataSource"));
    }

    private static int checkpoint(ConfigurableApplicationContext context, JobExecution execution) {
        StepExecution score = scoreStep(execution);
        StepExecution persisted = context.getBean(JobExplorer.class).getStepExecution(execution.getId(), score.getId());
        assertThat(persisted).isNotNull();
        return persisted.getExecutionContext().getInt(READER_POSITION);
    }

    private static StepExecution scoreStep(JobExecution execution) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals("calculateWeeklyScores")).findFirst().orElseThrow();
    }

    private static List<Map<String, Object>> scores(JdbcTemplate jdbc, WeeklyRankingKey key) {
        return jdbc.queryForList("SELECT product_id, score FROM weekly_ranking_score WHERE snapshot_id=? ORDER BY product_id",
            key.snapshotId());
    }

    private static List<Map<String, Object>> results(JdbcTemplate jdbc, WeeklyRankingKey key) {
        return jdbc.queryForList("SELECT product_id, score, ranking_position FROM weekly_ranking_result"
            + " WHERE snapshot_id=? ORDER BY ranking_position", key.snapshotId());
    }

    private static void assertPublished(JdbcTemplate jdbc, WeeklyRankingKey key) {
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_publication WHERE period_start=?",
            String.class, key.periodStart())).isEqualTo(key.snapshotId());
        assertThat(jdbc.queryForObject("SELECT calculation_state FROM weekly_ranking_snapshot WHERE snapshot_id=?",
            String.class, key.snapshotId())).isEqualTo("COMPLETE");
    }

    private static List<Map<String, Object>> inputs(JdbcTemplate jdbc, WeeklyRankingKey key) {
        return jdbc.queryForList("SELECT * FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY order_id, product_id", key.snapshotId());
    }

    private static void assertExpectedScores(List<Map<String, Object>> scores) {
        assertThat(scores).containsExactly(Map.of("product_id", 7L, "score", 5L),
            Map.of("product_id", 8L, "score", 1L), Map.of("product_id", 9L, "score", 5L));
    }

    private static void seedFourPurchases(JdbcTemplate jdbc) {
        seedPurchase(jdbc, 42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
        jdbc.update("INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano)"
            + " VALUES (42, 8, 1, ?, 0)", Instant.parse("2026-08-17T01:00:00Z").getEpochSecond());
        seedPurchase(jdbc, 43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
        seedPurchase(jdbc, 44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
    }

    private static void seedPurchase(JdbcTemplate jdbc, long orderId, long productId, int quantity, Instant confirmedAt) {
        jdbc.update("""
            INSERT INTO processed_order_confirmation (event_id, order_id, event_type, schema_version,
                confirmed_at_second, confirmed_at_nano) VALUES (?, ?, 'OrderConfirmed', 1, ?, ?)
            """, "weekly-job-test-" + orderId, orderId, confirmedAt.getEpochSecond(), confirmedAt.getNano());
        jdbc.update("""
            INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano)
            VALUES (?, ?, ?, ?, ?)
            """, orderId, productId, quantity, confirmedAt.getEpochSecond(), confirmedAt.getNano());
    }

    private enum FailureBoundary { AFTER_WRITER_SQL, AFTER_WRITER_CHECKED_SQL, AFTER_CHECKPOINT_SQL }

    private static final class InjectedFailure extends RuntimeException {
        InjectedFailure(String message) { super(message); }
    }

    private record ConnectionPair(long writerConnection, long checkpointConnection) {}

    static final class SqlObservation {
        final AtomicLong connections = new AtomicLong();
        final List<ConnectionPair> pairs = new ArrayList<>();
        final List<Long> rolledBackConnections = new ArrayList<>();
        Long pendingWriter;
        Long nineWriterConnection;
        boolean actualNineWrite;
        boolean failNextCheckpoint;
        boolean failedAfterCheckpointSql;
        boolean checkedAfterNine;
        boolean checkedFailureDelivered;
        boolean failAfterPublicationCommit;
        boolean observedCommittedPublication;

        void afterStatement(String sql, long connection, boolean autoCommit) throws SQLException {
            String normalized = sql.toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (normalized.startsWith("INSERT INTO WEEKLY_RANKING_SCORE") || normalized.startsWith("UPDATE WEEKLY_RANKING_SCORE")) {
                if (autoCommit) {
                    throw new SQLException("Observed score SQL outside a transaction");
                }
                pendingWriter = connection;
            }
            if (normalized.startsWith("UPDATE BATCH_STEP_EXECUTION_CONTEXT") && pendingWriter != null) {
                if (autoCommit) {
                    throw new SQLException("Observed chunk checkpoint outside a transaction");
                }
                pairs.add(new ConnectionPair(pendingWriter, connection));
                pendingWriter = null;
                if (failNextCheckpoint) {
                    failNextCheckpoint = false;
                    failedAfterCheckpointSql = true;
                    throw new SQLException("Injected after real checkpoint SQL, before commit", "HY000");
                }
            }
        }

        void afterTransactionCompletion(String operation, long connection) {
            if (operation.equals("rollback")) {
                rolledBackConnections.add(connection);
            }
            // A later metadata-cleanup transaction is not the failed chunk's checkpoint write.
            if (pendingWriter != null && pendingWriter == connection) {
                pendingWriter = null;
            }
        }
    }

    static final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource pool;
        private final SqlObservation observation;

        ObservedDataSource(HikariDataSource pool, SqlObservation observation) {
            super(pool);
            this.pool = pool;
            this.observation = observation;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection target = pool.getConnection();
            long id = observation.connections.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result = invoke(target, method, arguments);
                    if ((method.getName().equals("commit") || method.getName().equals("rollback"))
                        && (arguments == null || arguments.length == 0)) {
                        observation.afterTransactionCompletion(method.getName(), id);
                    }
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                        String sql = (String) arguments[0];
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (statementProxy, statementMethod, statementArguments) -> {
                                Object value = invoke(statement, statementMethod, statementArguments);
                                if (statementMethod.getName().startsWith("execute")) {
                                    observation.afterStatement(sql, id, target.getAutoCommit());
                                }
                                return value;
                            });
                    }
                    return result;
                });
        }

        @Override
        public void close() { pool.close(); }

        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({WeeklyRankingJobConfig.class, WeeklyRankingSnapshotService.class,
        WeeklyRankingPublicationService.class, JdbcRankingPublicationStore.class})
    static class Config {
        @Bean
        static BeanPostProcessor checkedWriterBoundary(ObjectProvider<SqlObservation> observations) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (beanName.equals("publishWeeklyRanking") && bean instanceof TaskletStep publication) {
                        publication.registerChunkListener(new ChunkListener() {
                            @Override
                            public void afterChunk(ChunkContext chunk) {
                                SqlObservation observation = observations.getObject();
                                if (!observation.failAfterPublicationCommit) {
                                    return;
                                }
                                observation.failAfterPublicationCommit = false;
                                JobParameters parameters = chunk.getStepContext().getStepExecution().getJobParameters();
                                WeeklyRankingKey key = new WeeklyRankingKey(LocalDate.parse(parameters.getString("periodStart")),
                                    Math.toIntExact(parameters.getLong("revision")));
                                // A separate pool/connection sees the commit before Batch marks the Step COMPLETED.
                                try (HikariDataSource independent = newPool()) {
                                    JdbcTemplate jdbc = new JdbcTemplate(independent);
                                    assertPublished(jdbc, key);
                                    assertThat(results(jdbc, key)).hasSize(3);
                                    observation.observedCommittedPublication = true;
                                }
                                throw new InjectedFailure("After publication commit, before final Batch status");
                            }
                        });
                    }
                    if (!beanName.equals("scopedTarget.weeklyRankingWriter")) {
                        return bean;
                    }
                    // Inject at ItemWriter.write, whose contract declares Exception. Throwing through
                    // the repository interface would wrap IOException as UndeclaredThrowableException.
                    return Proxy.newProxyInstance(ItemWriter.class.getClassLoader(), new Class<?>[]{ItemWriter.class},
                        (proxy, method, arguments) -> {
                            Object result = ObservedDataSource.invoke(bean, method, arguments);
                            if (method.getName().equals("write")) {
                                SqlObservation observation = observations.getObject();
                                if (observation.checkedAfterNine) {
                                    observation.checkedAfterNine = false;
                                    observation.checkedFailureDelivered = true;
                                    throw new IOException("Checked failure after real production ItemWriter SQL, before checkpoint");
                                }
                            }
                            return result;
                        });
                }
            };
        }

        @Bean SqlObservation sqlObservation() { return new SqlObservation(); }

        @Primary
        @Bean(name = "mySqlMainDataSource", destroyMethod = "close")
        ObservedDataSource dataSource(SqlObservation observation) { return new ObservedDataSource(newPool(), observation); }

        @Bean("weeklyRankingClock")
        Clock clock() { return Clock.fixed(Instant.parse("2026-08-24T00:30:00Z"), ZoneOffset.UTC); }

        @Bean
        RankingSnapshotStore snapshotStore(JdbcTemplate jdbc) { return spy(new JdbcRankingSnapshotStore(jdbc)); }

        @Bean
        RankingScoreStore scoreStore(JdbcTemplate jdbc) { return spy(new JdbcRankingScoreStore(jdbc)); }
    }
}
