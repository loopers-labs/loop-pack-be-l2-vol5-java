package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.ranking.command.FreezeRankingSnapshotCommand;
import com.loopers.application.ranking.command.PublishWeeklyRankingCommand;
import com.loopers.application.ranking.result.WeeklyRankingPublicationResult;
import com.loopers.application.ranking.service.CalculateWeeklyScoreService;
import com.loopers.application.ranking.service.FreezeRankingSnapshotService;
import com.loopers.application.ranking.service.PublishWeeklyRankingService;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WeeklyRankingPublicationTest {
    private static final String GOLDEN = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";
    private PreservedMySql database;
    private int revision;

    @BeforeAll
    void preparePrivateDatabase() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connect()) {
            for (String script : List.of("preservation/001-batch-metadata-mysql.sql",
                "preservation/014-weekly-ranking-snapshots.sql", "preservation/015-weekly-ranking-scores.sql",
                "preservation/016-weekly-ranking-publication.sql", "ranking/purchase-input-probe.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
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
    @DisplayName("주간 Job이 입력 고정·계산·공개를 수행하고 동점 순위와 checksum을 저장한다")
    void completesPublicationAfterCalculation() throws Exception {
        var command = command("2026-08-17");
        try (var context = start()) {
            var execution = launch(context, command);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("freezeWeeklyRankingInput", "calculateWeeklyScores", "publishWeeklyRanking");
            assertGolden(command);
            assertThat(pointer(command)).containsExactly(command.snapshotId() + ":" + command.revision());
        }
    }

    @Test
    @DisplayName("같은 snapshot 재공개는 결과·checksum·완성시각을 보존하고 stage를 다시 복사하지 않는다")
    void reusesImmutableCompletion() throws Exception {
        try (var context = start()) {
            var command = calculate(context, "2026-08-17");
            assertThat(publish(context, command)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            var completed = completion(command);
            execute("UPDATE weekly_ranking_score_stage SET score=999 WHERE snapshot_id='" + command.snapshotId() + "'");
            assertThat(publish(context, command)).isEqualTo(WeeklyRankingPublicationResult.ALREADY_PUBLISHED);
            assertGolden(command);
            assertThat(completion(command)).isEqualTo(completed);
        }
    }

    @Test
    @DisplayName("빈 주간도 완성본·pointer와 빈 SHA-256·상품수0을 저장한다")
    void publishesEmptySnapshot() throws Exception {
        try (var context = start()) {
            var command = calculate(context, "2026-09-07");
            assertThat(publish(context, command)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            assertThat(rankings(command)).isEmpty();
            assertThat(completion(command).getFirst()).startsWith("0:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855:");
            assertThat(pointer(command)).containsExactly(command.snapshotId() + ":" + command.revision());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "score", "same-count"})
    @DisplayName("stage 누락·추가·오점수·같은 행수의 상품 교체는 공개하지 않는다")
    void rejectsIncorrectScores(String corruption) throws Exception {
        try (var context = start()) {
            var previous = calculate(context, "2026-08-17");
            assertThat(publish(context, previous)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            var candidate = calculate(context, "2026-08-17");
            String id = candidate.snapshotId().toString();
            switch (corruption) {
                case "missing" -> execute("DELETE FROM weekly_ranking_score_stage WHERE snapshot_id='" + id + "' AND product_id=9");
                case "extra" -> execute("INSERT INTO weekly_ranking_score_stage VALUES ('" + id + "',99,5)");
                case "score" -> execute("UPDATE weekly_ranking_score_stage SET score=6 WHERE snapshot_id='" + id + "' AND product_id=9");
                case "same-count" -> execute("UPDATE weekly_ranking_score_stage SET product_id=99 WHERE snapshot_id='" + id + "' AND product_id=9");
                default -> throw new IllegalArgumentException(corruption);
            }
            assertThatThrownBy(() -> publish(context, candidate)).isInstanceOf(SnapshotIntegrityException.class);
            assertThat(rankings(candidate)).isEmpty();
            assertThat(completion(candidate)).isEmpty();
            assertThat(pointer(candidate)).isEqualTo(pointer(previous));
            assertGolden(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"score", "ranking_position"})
    @DisplayName("복사 target 변조도 frozen 입력과 대조해 거절하고 첫 pointer 예약까지 rollback한다")
    void validatesCopiedTarget(String column) throws Exception {
        try (var context = start()) {
            var command = calculate(context, "2026-08-10");
            execute("CREATE TRIGGER c05_corrupt_target BEFORE INSERT ON weekly_rankings FOR EACH ROW SET NEW."
                + column + "=NEW." + column + "+1");
            try {
                assertThatThrownBy(() -> publish(context, command)).isInstanceOf(SnapshotIntegrityException.class);
                assertThat(rankings(command)).isEmpty();
                assertThat(completion(command)).isEmpty();
                assertThat(pointer(command)).isEmpty();
            } finally {
                execute("DROP TRIGGER c05_corrupt_target");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ranking", "completion", "pointer"})
    @DisplayName("순위 일부·완성기록·pointer 저장 오류는 새 결과 전체를 rollback하고 이전 공개본을 보존한다")
    void rollsBackPublication(String boundary) throws Exception {
        try (var context = start()) {
            var previous = calculate(context, "2026-08-17");
            assertThat(publish(context, previous)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            var candidate = calculate(context, "2026-08-17");
            String event = switch (boundary) {
                case "ranking" -> "BEFORE INSERT ON weekly_rankings";
                case "completion" -> "AFTER INSERT ON weekly_ranking_completions";
                case "pointer" -> "AFTER UPDATE ON weekly_ranking_publications";
                default -> throw new IllegalArgumentException(boundary);
            };
            String extra = boundary.equals("ranking") ? " AND NEW.product_id=9" : "";
            execute("CREATE TRIGGER c05_publication_failure " + event + " FOR EACH ROW BEGIN IF NEW.snapshot_id='"
                + candidate.snapshotId() + "'" + extra + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C05_PUBLICATION_ROLLBACK'; END IF; END");
            try {
                assertThatThrownBy(() -> publish(context, candidate)).isInstanceOf(DataAccessException.class)
                    .hasStackTraceContaining("C05_PUBLICATION_ROLLBACK");
                assertThat(rankings(candidate)).isEmpty();
                assertThat(completion(candidate)).isEmpty();
                assertThat(pointer(candidate)).containsExactly(previous.snapshotId() + ":" + previous.revision());
                assertGolden(previous);
            } finally {
                execute("DROP TRIGGER c05_publication_failure");
            }
            assertThat(publish(context, candidate)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            assertGolden(candidate);
        }
    }

    @Test
    @DisplayName("독립 두 context의 동일 snapshot 동시 최초 공개가 하나의 불변 완성본으로 수렴한다")
    void serializesConcurrentFirstPublication() throws Exception {
        try (var first = start(); var second = start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var command = calculate(first, "2026-09-14");
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var left = executor.submit(() -> { ready.countDown(); go.await(); return publish(first, command); });
            var right = executor.submit(() -> { ready.countDown(); go.await(); return publish(second, command); });
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(WeeklyRankingPublicationResult.PUBLISHED, WeeklyRankingPublicationResult.ALREADY_PUBLISHED);
            assertThat(completion(command)).hasSize(1);
            assertThat(pointer(command)).containsExactly(command.snapshotId() + ":" + command.revision());
        }
    }

    @Test
    @DisplayName("다른 revision의 동시 최초 공개에서도 높은 revision이 최종 pointer가 된다")
    void neverRegressesAcrossConcurrentRevisions() throws Exception {
        try (var first = start(); var second = start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var older = calculate(first, "2026-09-21");
            var newer = calculate(first, "2026-09-21");
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var left = executor.submit(() -> { ready.countDown(); go.await(); return publish(first, older); });
            var right = executor.submit(() -> { ready.countDown(); go.await(); return publish(second, newer); });
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(right.get(30, TimeUnit.SECONDS)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            assertThat(left.get(30, TimeUnit.SECONDS)).isIn(WeeklyRankingPublicationResult.PUBLISHED, WeeklyRankingPublicationResult.SUPERSEDED);
            assertThat(pointer(newer)).containsExactly(newer.snapshotId() + ":" + newer.revision());
        }
    }

    @Test
    @DisplayName("새 revision 뒤 오래된 Job은 COMPLETED·SUPERSEDED로 종료하고 새 완성본을 만들지 않는다")
    void completesSupersededJob() throws Exception {
        try (var context = start()) {
            var older = calculate(context, "2026-08-17");
            var newer = calculate(context, "2026-08-17");
            assertThat(publish(context, newer)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            var execution = launch(context, older);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode()).isEqualTo("SUPERSEDED");
            assertThat(completion(older)).isEmpty();
            assertThat(rankings(older)).isEmpty();
            assertThat(pointer(older)).containsExactly(newer.snapshotId() + ":" + newer.revision());
            assertGolden(newer);
        }
    }

    @Test
    @DisplayName("공개 commit 후 최종 JobExecution 저장만 실패하면 새 공개본·DB STARTED를 보존하고 재launch를 거절한다")
    void preservesPublicationWhenFinalJobStatusWriteFails() throws Exception {
        try (var context = start()) {
            var previous = calculate(context, "2026-08-17");
            assertThat(publish(context, previous)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            var candidate = command("2026-08-17");
            execute("CREATE TRIGGER c05_final_job_failure BEFORE UPDATE ON BATCH_JOB_EXECUTION FOR EACH ROW BEGIN "
                + "IF NEW.STATUS='COMPLETED' AND EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION_PARAMS p "
                + "WHERE p.JOB_EXECUTION_ID=NEW.JOB_EXECUTION_ID AND p.PARAMETER_NAME='snapshotId' AND p.PARAMETER_VALUE='"
                + candidate.snapshotId() + "') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C05_FINAL_JOB_STATUS_ONLY'; END IF; END");
            try {
                assertThatThrownBy(() -> launch(context, candidate)).hasStackTraceContaining("C05_FINAL_JOB_STATUS_ONLY");
                assertGolden(candidate);
                assertThat(pointer(candidate)).containsExactly(candidate.snapshotId() + ":" + candidate.revision());
                var jobs = query("SELECT j.JOB_EXECUTION_ID,j.STATUS,j.END_TIME FROM BATCH_JOB_EXECUTION j "
                    + "JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID=j.JOB_EXECUTION_ID "
                    + "WHERE p.PARAMETER_NAME='snapshotId' AND p.PARAMETER_VALUE='" + candidate.snapshotId() + "'", 3);
                assertThat(jobs).hasSize(1);
                String executionId = jobs.getFirst().split(":")[0];
                assertThat(jobs).containsExactly(executionId + ":STARTED:null");
                assertThat(query("SELECT STATUS FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=" + executionId
                    + " AND STEP_NAME='publishWeeklyRanking'", 1)).containsExactly("COMPLETED");
                var before = query("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", 1);
                try (var fresh = start()) {
                    assertThatThrownBy(() -> launch(fresh, candidate)).isInstanceOf(JobExecutionAlreadyRunningException.class);
                }
                assertThat(query("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", 1)).isEqualTo(before);
                assertThat(query("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID=" + executionId, 1)).containsExactly("STARTED");
                assertGolden(candidate);
                System.out.printf("C05 final-job-write snapshot=%s revision=%d execution=%s pointer=%s job=STARTED publishStep=COMPLETED relaunch=REJECTED executionsUnchanged=%s checksum=%s%n",
                    candidate.snapshotId(), candidate.revision(), executionId, pointer(candidate), before, GOLDEN);
            } finally {
                execute("DROP TRIGGER c05_final_job_failure");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"score", "checksum"})
    @DisplayName("재공개는 완성 행 변조를 덮어써 복구하지 않는다")
    void rejectsCorruptedCompletedResult(String corruption) throws Exception {
        try (var context = start()) {
            var command = calculate(context, "2026-08-17");
            assertThat(publish(context, command)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            if (corruption.equals("score")) {
                execute("UPDATE weekly_rankings SET score=6 WHERE snapshot_id='" + command.snapshotId() + "' AND product_id=9");
            } else {
                execute("UPDATE weekly_ranking_completions SET result_checksum=REPEAT('a',64) WHERE snapshot_id='"
                    + command.snapshotId() + "'");
            }
            assertThatThrownBy(() -> publish(context, command)).isInstanceOf(SnapshotIntegrityException.class);
            if (corruption.equals("score")) {
                assertThat(query("SELECT score FROM weekly_rankings WHERE snapshot_id='" + command.snapshotId()
                    + "' AND product_id=9", 1)).containsExactly("6");
            } else {
                assertThat(completion(command).getFirst()).startsWith("3:" + "a".repeat(64) + ":");
            }
            assertThat(pointer(command)).containsExactly(command.snapshotId() + ":" + command.revision());
        }
    }

    @Test
    @DisplayName("외부 RR TX rollback에도 독립 RC 공개 commit은 남고 외부 격리수준은 복원된다")
    void commitsIndependentlyOfOuterRollback() throws Exception {
        try (var context = start()) {
            var command = calculate(context, "2026-08-17");
            execute("CREATE TABLE c05_publication_isolation (snapshot_id CHAR(36) PRIMARY KEY, isolation_level VARCHAR(64) NOT NULL)");
            execute("CREATE TRIGGER c05_capture_isolation AFTER INSERT ON weekly_ranking_completions FOR EACH ROW "
                + "INSERT INTO c05_publication_isolation VALUES (NEW.snapshot_id,@@transaction_isolation)");
            try {
                var outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                outer.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                    assertThat(publish(context, command)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
                    assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                    status.setRollbackOnly();
                });
                assertThat(query("SELECT isolation_level FROM c05_publication_isolation WHERE snapshot_id='"
                    + command.snapshotId() + "'", 1)).containsExactly("READ-COMMITTED");
                assertGolden(command);
                assertThat(pointer(command)).containsExactly(command.snapshotId() + ":" + command.revision());
            } finally {
                execute("DROP TRIGGER c05_capture_isolation");
            }
        }
    }

    @Test
    @DisplayName("입력 checksum 변조와 snapshot의 다른 계약은 공개 전에 거절한다")
    void rejectsInputTamperingAndContractMismatch() throws Exception {
        try (var context = start()) {
            var command = calculate(context, "2026-08-17");
            assertThatThrownBy(() -> publish(context, new PublishWeeklyRankingCommand(command.snapshotId(), command.periodStart(), command.revision()+1)))
                .isInstanceOf(SnapshotConflictException.class);
            execute("UPDATE weekly_ranking_snapshot_inputs SET quantity=72 WHERE snapshot_id='" + command.snapshotId() + "' AND product_id=9");
            assertThatThrownBy(() -> publish(context, command)).isInstanceOf(SnapshotIntegrityException.class);
            assertThat(rankings(command)).isEmpty();
            assertThat(completion(command)).isEmpty();
        }
    }

    @Test
    @DisplayName("계산 실패 동안 이전 공개본을 유지하고 같은 instance 재시작은 같은 정답으로 공개한다")
    void preservesPreviousPublicationWhileRestarting() throws Exception {
        PublishWeeklyRankingCommand restarted;
        JobExecution failed;
        try (var context = start()) {
            var previous = calculate(context, "2026-08-17");
            assertThat(publish(context, previous)).isEqualTo(WeeklyRankingPublicationResult.PUBLISHED);
            restarted = command("2026-08-17");
            context.getBean(RankingJobTestControl.class).failure = RankingJobTestControl.Failure.BEFORE_NINE;
            failed = launch(context, restarted);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(pointer(restarted)).containsExactly(previous.snapshotId() + ":" + previous.revision());
            assertThat(completion(restarted)).isEmpty();
            assertGolden(previous);
        }
        try (var context = start()) {
            var completed = launch(context, restarted);
            assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(completed.getJobInstance().getId()).isEqualTo(failed.getJobInstance().getId());
            assertThat(context.getBean(RankingJobTestControl.class).processed).containsExactly(9L);
            assertGolden(restarted);
        }
    }

    private ConfigurableApplicationContext start() {
        return PreservationContexts.start(RankingJobRestartTest.Configuration.class,
            database.arguments("--spring.batch.job.name=weeklyRanking", "--ranking.weekly.chunk-size=2"));
    }

    private PublishWeeklyRankingCommand command(String period) {
        return new PublishWeeklyRankingCommand(UUID.randomUUID(), LocalDate.parse(period), ++revision);
    }

    private PublishWeeklyRankingCommand calculate(ConfigurableApplicationContext context, String period) {
        var command = command(period);
        context.getBean(FreezeRankingSnapshotService.class).freeze(
            new FreezeRankingSnapshotCommand(command.snapshotId(), command.periodStart(), command.revision()));
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var calculator = context.getBean(CalculateWeeklyScoreService.class);
            var products = jdbc.queryForList("SELECT DISTINCT product_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY product_id",
                Long.class, command.snapshotId().toString());
            calculator.write(products.stream().map(product -> calculator.calculate(command.snapshotId(), product)).toList());
        });
        return command;
    }

    private WeeklyRankingPublicationResult publish(ConfigurableApplicationContext context, PublishWeeklyRankingCommand command) {
        return context.getBean(PublishWeeklyRankingService.class).publish(command);
    }

    private JobExecution launch(ConfigurableApplicationContext context, PublishWeeklyRankingCommand command) throws Exception {
        var parameters = new JobParametersBuilder().addString("periodStart", command.periodStart().toString())
            .addLong("revision", (long) command.revision()).addString("snapshotId", command.snapshotId().toString()).toJobParameters();
        return context.getBean(JobLauncher.class).run(context.getBean("weeklyRanking", Job.class), parameters);
    }

    private List<String> rankings(PublishWeeklyRankingCommand command) throws Exception {
        return query("SELECT product_id,score,ranking_position FROM weekly_rankings WHERE snapshot_id='" + command.snapshotId()
            + "' ORDER BY ranking_position", 3);
    }

    private List<String> completion(PublishWeeklyRankingCommand command) throws Exception {
        return query("SELECT product_count,result_checksum,completed_at FROM weekly_ranking_completions WHERE snapshot_id='"
            + command.snapshotId() + "'", 3);
    }

    private List<String> pointer(PublishWeeklyRankingCommand command) throws Exception {
        return query("SELECT snapshot_id,revision FROM weekly_ranking_publications WHERE period_start='" + command.periodStart() + "'", 2);
    }

    private void assertGolden(PublishWeeklyRankingCommand command) throws Exception {
        assertThat(rankings(command)).containsExactly("7:5:1", "9:5:2", "8:1:3");
        assertThat(completion(command)).hasSize(1);
        assertThat(completion(command).getFirst()).startsWith("3:" + GOLDEN + ":");
    }

    private List<String> query(String sql, int columns) throws Exception {
        var result = new ArrayList<String>();
        // Bypass the application pool and any application transaction.
        try (var connection = database.connect(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                var values = new ArrayList<String>();
                for (int column = 1; column <= columns; column++) {
                    values.add(String.valueOf(rows.getString(column)));
                }
                result.add(String.join(":", values));
            }
        }
        return result;
    }

    private void execute(String sql) throws Exception {
        try (var connection = database.connectAsAdministrator(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
