package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RankingJobRestartTest {
    private PreservedMySql database;
    private long revision;

    @BeforeAll
    void preparePrivateDatabase() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connect()) {
            for (String script : List.of("preservation/001-batch-metadata-mysql.sql",
                "preservation/014-weekly-ranking-snapshots.sql", "ranking/purchase-input-probe.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
            var stage = new ClassPathResource("preservation/015-weekly-ranking-scores.sql");
            if (stage.exists()) {
                ScriptUtils.executeSqlScript(connection, stage);
            }
        }
    }

    @AfterAll
    void closePrivateDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("기본 NONE 선택에서는 주간 Job을 등록하거나 실행하지 않는다")
    void requiresExplicitJobSelection() {
        try (var context = PreservationContexts.start(Configuration.class,
            database.arguments("--spring.batch.job.name=NONE"))) {
            assertThat(context.containsBean("weeklyRanking")).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = RankingJobTestControl.Failure.class, names = {"BEFORE_NINE", "BEFORE_COMMIT", "STOP_AFTER_CHUNK"})
    @DisplayName("실제 점수 7·8과 위치2를 보존하고 새 context에서 9부터 재개한다")
    void resumesScoresFromDurableCheckpoint(RankingJobTestControl.Failure failure) throws Exception {
        var parameters = parameters("2026-08-17");
        JobExecution first;
        try (var context = start()) {
            var control = context.getBean(RankingJobTestControl.class);
            control.failure = failure;
            first = launch(context, parameters);
            assertThat(first.getStatus()).isEqualTo(failure == RankingJobTestControl.Failure.STOP_AFTER_CHUNK
                ? BatchStatus.STOPPED : BatchStatus.FAILED);
            assertThat(scores(parameters)).isEqualTo(Map.of(7L, 5L, 8L, 1L));
            assertGoldenFrozenInput(context, parameters);
            assertThat(checkpoint(context, first).getExecutionContext().getInt("weeklyRankingProducts.read.count")).isEqualTo(2);
            assertThat(context.getBean(RankingSnapshotTestConfiguration.Control.class).copies).hasValue(1);
            if (failure == RankingJobTestControl.Failure.BEFORE_COMMIT) {
                assertFailedTransaction(control.beforeFailure, context.getBean(ProbeConnectionObservation.class).events());
            }
            assertReleased(context);
        }
        try (var context = start()) {
            var second = launch(context, parameters);
            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
            assertThat(second.getId()).isNotEqualTo(first.getId());
            assertThat(context.getBean(RankingJobTestControl.class).processed).containsExactly(9L);
            assertThat(context.getBean(RankingSnapshotTestConfiguration.Control.class).copies).hasValue(0);
            assertThat(second.getStepExecutions()).extracting(StepExecution::getStepName).containsExactly("calculateWeeklyScores");
            assertThat(scores(parameters)).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
            assertGoldenFrozenInput(context, parameters);
            System.out.printf("C04 %s instance=%d executions=%d->%d checkpoint2 resumed=%s scores=%s%n",
                failure, first.getJobInstance().getId(), first.getId(), second.getId(),
                context.getBean(RankingJobTestControl.class).processed, scores(parameters));
            assertCommittedTransaction(context.getBean(ProbeConnectionObservation.class).events());
            assertReleased(context);
            var normalParameters = parameters("2026-08-17");
            assertThat(launch(context, normalParameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(normalParameters)).isEqualTo(scores(parameters));
            assertThat(inputFingerprint(normalParameters)).isEqualTo(inputFingerprint(parameters));
        }
    }

    @Test
    @DisplayName("동일 chunk를 두 번 써도 절대 점수이며 완료 instance는 다시 실행하지 않는다")
    void repeatedWriterIsAbsoluteAndCompletedLaunchIsRejected() throws Exception {
        var parameters = parameters("2026-08-17");
        try (var context = start()) {
            context.getBean(RankingJobTestControl.class).repeatWrites = true;
            assertThat(launch(context, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(parameters)).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
            assertThatThrownBy(() -> launch(context, parameters)).isInstanceOf(JobInstanceAlreadyCompleteException.class);
            assertThat(context.getBean(RankingJobTestControl.class).processed).containsExactly(7L, 8L, 9L);
        }
    }

    @Test
    @DisplayName("빈 입력을 0행으로 완료하고 다른 snapshot의 상품은 읽지 않는다")
    void completesEmptySnapshotAndIsolatesOtherWeek() throws Exception {
        try (var context = start()) {
            var other = parameters("2026-08-10");
            assertThat(launch(context, other).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(other)).isEqualTo(Map.of(6L, 99L));
            var empty = parameters("2026-08-03");
            assertThat(launch(context, empty).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(empty)).isEmpty();
        }
    }

    @Test
    @DisplayName("재시작 사이 늦은 원본은 frozen 입력에 추가하지 않고 사본 변조는 계산 전에 거절한다")
    void preservesLateSourceAndRejectsCorruptedFrozenInput() throws Exception {
        var parameters = parameters("2026-08-17");
        try (var context = start()) {
            context.getBean(RankingJobTestControl.class).failure = RankingJobTestControl.Failure.BEFORE_NINE;
            assertThat(launch(context, parameters).getStatus()).isEqualTo(BatchStatus.FAILED);
        }
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO order_confirmation_inbox
                (id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
                VALUES (46,'00000000-0000-0000-0000-000000000046',46,'OrderConfirmed',1,
                    '2026-08-19 02:00:00','{}','2026-08-24 00:02:00')
                """);
            statement.executeUpdate("""
                INSERT INTO product_purchase_activities (inbox_id,order_id,product_id,quantity,confirmed_at)
                VALUES (46,46,9,72,'2026-08-19 02:00:00')
                """);
        }
        try (var context = start()) {
            assertThat(launch(context, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(parameters).get(9L)).isEqualTo(5L);
            var newRevision = parameters("2026-08-17");
            assertThat(launch(context, newRevision).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(newRevision).get(9L)).isEqualTo(77L);
            assertThat(scores(parameters).get(9L)).isEqualTo(5L);
        } finally {
            try (var connection = database.connect(); var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM product_purchase_activities WHERE order_id=46");
                statement.executeUpdate("DELETE FROM order_confirmation_inbox WHERE id=46");
            }
        }
        var corrupted = parameters("2026-08-17");
        try (var context = start()) {
            context.getBean(RankingJobTestControl.class).failure = RankingJobTestControl.Failure.BEFORE_NINE;
            assertThat(launch(context, corrupted).getStatus()).isEqualTo(BatchStatus.FAILED);
        }
        try (var connection = database.connect(); var statement = connection.prepareStatement(
            "UPDATE weekly_ranking_snapshot_inputs SET quantity=6 WHERE snapshot_id=? AND product_id=9")) {
            statement.setString(1, corrupted.getString("snapshotId"));
            statement.executeUpdate();
        }
        try (var context = start()) {
            assertThat(launch(context, corrupted).getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(context.getBean(RankingJobTestControl.class).processed).isEmpty();
            assertThat(scores(corrupted)).isEqualTo(Map.of(7L, 5L, 8L, 1L));
            assertThat(context.getBean(RankingSnapshotTestConfiguration.Control.class).copies).hasValue(0);
        }
    }

    @Test
    @DisplayName("이름·타입·값·식별 여부가 잘못된 parameter는 새 실행과 snapshot을 만들기 전에 거절한다")
    void rejectsInvalidIdentityBeforeCreatingExecution() throws Exception {
        try (var context = start()) {
            job(context);
            var base = parameters("2026-08-17");
            var invalid = new ArrayList<JobParameters>();
            invalid.add(new JobParametersBuilder(base).addLong("run.id", 1L).toJobParameters());
            invalid.add(new JobParametersBuilder(base).addString("revision", "1").toJobParameters());
            invalid.add(new JobParametersBuilder(base).addLong("revision", 1L, false).toJobParameters());
            invalid.add(new JobParametersBuilder(base).addLong("revision", 0L).toJobParameters());
            invalid.add(new JobParametersBuilder(base).addLong("revision", 2147483648L).toJobParameters());
            invalid.add(new JobParametersBuilder(base).addString("periodStart", "2026-08-18").toJobParameters());
            invalid.add(new JobParametersBuilder(base).addString("snapshotId", "1-1-1-1-1").toJobParameters());
            invalid.add(new JobParametersBuilder().addString("snapshotId", UUID.randomUUID().toString()).toJobParameters());
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            long before = jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class);
            for (var parameters : invalid) {
                assertThatThrownBy(() -> launch(context, parameters)).isInstanceOf(JobParametersInvalidException.class);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class)).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"UNKNOWN", "ABANDONED", "STARTING", "STARTED", "STOPPING"})
    @DisplayName("미확정·포기·실행 중 metadata를 새 실행이나 성공으로 바꾸지 않는다")
    void rejectsUnsafeExistingState(BatchStatus status) throws Exception {
        try (var context = start()) {
            job(context);
            var parameters = parameters("2026-08-17");
            var repository = context.getBean(JobRepository.class);
            var execution = repository.createJobExecution("weeklyRanking", parameters);
            execution.setStatus(status);
            repository.update(execution);
            Class<? extends Exception> expected = status == BatchStatus.UNKNOWN ? JobRestartException.class
                : status == BatchStatus.ABANDONED ? JobInstanceAlreadyCompleteException.class : JobExecutionAlreadyRunningException.class;
            assertThatThrownBy(() -> launch(context, parameters)).isInstanceOf(expected);
            assertThat(repository.findJobExecutions(execution.getJobInstance())).hasSize(1);
        }
    }

    @Test
    @DisplayName("공유 MySQL의 독립 두 context에서 같은 식별값으로 계산을 중복 시작하지 않는다")
    void sharesRepositoryAcrossContexts() throws Exception {
        var parameters = parameters("2026-08-17");
        try (var first = start(); var second = start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            job(first);
            job(second);
            var control = first.getBean(RankingJobTestControl.class);
            control.failure = RankingJobTestControl.Failure.BLOCK_NINE;
            var running = executor.submit(() -> launch(first, parameters));
            try {
                assertThat(control.entered.await(30, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> launch(second, parameters)).isInstanceOf(JobExecutionAlreadyRunningException.class);
                assertThat(second.getBean(RankingJobTestControl.class).processed).isEmpty();
            } finally {
                control.release.countDown();
            }
            var completed = running.get(30, TimeUnit.SECONDS);
            assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(second.getBean(JobRepository.class).findJobExecutions(completed.getJobInstance())).hasSize(1);
            assertThat(scores(parameters)).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
        }
    }

    @Test
    @DisplayName("동시에 launch한 독립 context의 실행 생성 경합에서 한 실행만 계산한다")
    void racesExecutionCreationAcrossContexts() throws Exception {
        var parameters = parameters("2026-08-17");
        try (var first = start(); var second = start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            job(first);
            job(second);
            assertThat(first.getBean(DataSource.class)).isNotSameAs(second.getBean(DataSource.class));
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var left = executor.submit(attempt(first, parameters, ready, go));
            var right = executor.submit(attempt(second, parameters, ready, go));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var attempts = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
            assertThat(attempts.stream().filter(value -> value.execution() != null
                && value.execution().getStatus() == BatchStatus.COMPLETED)).hasSize(1);
            var rejected = attempts.stream().filter(value -> value.failure() != null).findFirst().orElseThrow();
            assertThat(rejected.failure()).isInstanceOfAny(JobExecutionAlreadyRunningException.class,
                JobInstanceAlreadyCompleteException.class, DataAccessException.class);
            var instance = first.getBean(JobRepository.class).getJobInstance("weeklyRanking", parameters);
            assertThat(first.getBean(JobRepository.class).findJobExecutions(instance)).hasSize(1);
            var processed = new ArrayList<>(first.getBean(RankingJobTestControl.class).processed);
            processed.addAll(second.getBean(RankingJobTestControl.class).processed);
            assertThat(processed).containsExactly(7L, 8L, 9L);
            assertThat(scores(parameters)).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
        }
    }

    @Test
    @DisplayName("실제 JDBC SUM이 두 INT 최댓값의 합을 long 점수로 저장한다")
    void calculatesSumBeyondIntegerRangeThroughJdbc() throws Exception {
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE product_purchase_activities SET quantity=2147483647 WHERE product_id=7");
        }
        try (var context = start()) {
            var parameters = parameters("2026-08-17");
            assertThat(launch(context, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(scores(parameters).get(7L)).isEqualTo(4294967294L);
        } finally {
            try (var connection = database.connect(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE product_purchase_activities SET quantity=2 WHERE order_id=42 AND product_id=7");
                statement.executeUpdate("UPDATE product_purchase_activities SET quantity=3 WHERE order_id=43 AND product_id=7");
            }
        }
    }

    @Test
    @DisplayName("freeze 업무 commit 뒤 Step commit 실패는 기존 입력을 재사용하며 수렴한다")
    void reusesFreezeAfterStepCommitFailure() throws Exception {
        var parameters = parameters("2026-08-17");
        try (var context = start()) {
            context.getBean(RankingJobTestControl.class).failure = RankingJobTestControl.Failure.FREEZE_METADATA;
            assertThat(launch(context, parameters).getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(inputFingerprint(parameters)).hasSize(4);
            assertThat(scores(parameters)).isEmpty();
            assertThat(context.getBean(RankingSnapshotTestConfiguration.Control.class).copies).hasValue(1);
        }
        try (var context = start()) {
            assertThat(launch(context, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(context.getBean(RankingSnapshotTestConfiguration.Control.class).copies).hasValue(0);
            assertThat(scores(parameters)).isEqualTo(Map.of(7L, 5L, 8L, 1L, 9L, 5L));
        }
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"STARTED", "UNKNOWN"})
    @DisplayName("Job만 FAILED여도 이전 Step이 실행 중·미확정이면 재시작을 거절한다")
    void rejectsUnsafeStepDespiteFailedJob(BatchStatus status) throws Exception {
        try (var context = start()) {
            job(context);
            var parameters = parameters("2026-08-17");
            var repository = context.getBean(JobRepository.class);
            var execution = repository.createJobExecution("weeklyRanking", parameters);
            var step = new StepExecution("calculateWeeklyScores", execution);
            step.setStatus(status);
            repository.add(step);
            execution.setStatus(BatchStatus.FAILED);
            repository.update(execution);
            assertThatThrownBy(() -> launch(context, parameters)).isInstanceOf(status == BatchStatus.UNKNOWN
                ? JobRestartException.class : JobExecutionAlreadyRunningException.class);
            assertThat(repository.findJobExecutions(execution.getJobInstance())).hasSize(1);
        }
    }

    private record Attempt(JobExecution execution, Exception failure) {}

    private Callable<Attempt> attempt(ConfigurableApplicationContext context, JobParameters parameters,
                                      CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("C04 launch barrier timed out");
            }
            try {
                return new Attempt(launch(context, parameters), null);
            } catch (Exception exception) {
                return new Attempt(null, exception);
            }
        };
    }

    private ConfigurableApplicationContext start() {
        return PreservationContexts.start(Configuration.class, database.arguments("--spring.batch.job.name=weeklyRanking",
            "--ranking.weekly.chunk-size=2"));
    }

    private Job job(ConfigurableApplicationContext context) {
        assertThat(context.containsBean("weeklyRanking")).as("C04 production calculation Job").isTrue();
        return context.getBean("weeklyRanking", Job.class);
    }

    private JobExecution launch(ConfigurableApplicationContext context, JobParameters parameters) throws Exception {
        return context.getBean(JobLauncher.class).run(job(context), parameters);
    }

    private JobParameters parameters(String period) {
        return new JobParametersBuilder().addString("periodStart", period).addLong("revision", ++revision)
            .addString("snapshotId", UUID.randomUUID().toString()).toJobParameters();
    }

    private Map<Long, Long> scores(JobParameters parameters) throws Exception {
        var result = new java.util.TreeMap<Long, Long>();
        try (var connection = database.connect(); var statement = connection.prepareStatement(
            "SELECT product_id,score FROM weekly_ranking_score_stage WHERE snapshot_id=? ORDER BY product_id")) {
            statement.setString(1, parameters.getString("snapshotId"));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getLong(1), rows.getLong(2));
                }
            }
        }
        return result;
    }

    private List<String> inputFingerprint(JobParameters parameters) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = database.connect(); var statement = connection.prepareStatement(
            """
            SELECT order_id,product_id,quantity,DATE_FORMAT(confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ')
            FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY order_id,product_id
            """)) {
            statement.setString(1, parameters.getString("snapshotId"));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getLong(1) + ":" + rows.getLong(2) + ":" + rows.getInt(3) + ":" + rows.getString(4));
                }
            }
        }
        return result;
    }

    private void assertGoldenFrozenInput(ConfigurableApplicationContext context, JobParameters parameters) throws Exception {
        assertThat(inputFingerprint(parameters)).containsExactly("42:7:2:2026-08-17T01:00:00.000000Z",
            "42:8:1:2026-08-17T01:00:00.000000Z", "43:7:3:2026-08-18T01:00:00.000000Z", "44:9:5:2026-08-19T01:00:00.000000Z");
        var manifest = new JdbcTemplate(context.getBean(DataSource.class)).queryForMap(
            "SELECT state,input_count,input_checksum FROM weekly_ranking_snapshots WHERE snapshot_id=?", parameters.getString("snapshotId"));
        assertThat(manifest).containsEntry("state", "FROZEN").containsEntry("input_count", 4L)
            .containsEntry("input_checksum", "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298");
    }

    private StepExecution checkpoint(ConfigurableApplicationContext context, JobExecution execution) {
        var step = execution.getStepExecutions().stream().filter(value -> value.getStepName().equals("calculateWeeklyScores"))
            .findFirst().orElseThrow();
        return context.getBean(JobExplorer.class).getStepExecution(execution.getId(), step.getId());
    }

    private void assertReleased(ConfigurableApplicationContext context) throws Exception {
        assertThat(context.getBean(DataSource.class).unwrap(com.zaxxer.hikari.HikariDataSource.class)
            .getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private void assertCommittedTransaction(List<ProbeConnectionObservation.Event> events) {
        var write = events.stream().filter(event -> event.sql().startsWith("INSERT INTO weekly_ranking_score_stage"))
            .findFirst().orElseThrow();
        assertThat(write.autoCommit()).isFalse();
        assertThat(events).anyMatch(event -> event.connectionId() == write.connectionId()
            && event.sql().startsWith("UPDATE BATCH_STEP_EXECUTION_CONTEXT"));
        assertThat(events).anyMatch(event -> event.connectionId() == write.connectionId() && event.operation().equals("commit"));
    }

    private void assertFailedTransaction(List<ProbeConnectionObservation.Event> before, List<ProbeConnectionObservation.Event> all) {
        var write = before.stream().filter(event -> event.sql().startsWith("INSERT INTO weekly_ranking_score_stage"))
            .reduce((left, right) -> right).orElseThrow();
        assertThat(write.autoCommit()).isFalse();
        assertThat(before).anyMatch(event -> event.connectionId() == write.connectionId()
            && event.sql().startsWith("UPDATE BATCH_STEP_EXECUTION_CONTEXT"));
        assertThat(all).anyMatch(event -> event.connectionId() == write.connectionId() && event.operation().equals("rollback"));
        assertThat(all).noneMatch(event -> event.connectionId() == write.connectionId() && event.operation().equals("commit"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(RankingSnapshotTestConfiguration.class)
    @ComponentScan("com.loopers.batch.job.ranking")
    static class Configuration {
        @Bean
        static ProbeConnectionObservation scoreConnectionObservation() {
            return new ProbeConnectionObservation();
        }

        @Bean
        static RankingJobTestControl rankingJobTestControl(ProbeConnectionObservation observation) {
            return new RankingJobTestControl(observation);
        }
    }
}
