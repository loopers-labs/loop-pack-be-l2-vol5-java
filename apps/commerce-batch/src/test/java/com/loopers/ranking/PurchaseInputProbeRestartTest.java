package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.ranking.command.FreezeRankingSnapshotCommand;
import com.loopers.application.ranking.service.FreezeRankingSnapshotService;
import com.loopers.ranking.PurchaseInputProbeJob.FailurePoint;
import com.loopers.ranking.PurchaseInputProbeJob.ReaderKind;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.SimpleJobRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseInputProbeRestartTest {
    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 17);
    private static final String FIXTURE_HASH = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    private PreservedMySql database;
    private int revision;

    @BeforeAll
    void preparePrivateDatabaseOnce() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connect()) {
            for (String script : List.of("preservation/001-batch-metadata-mysql.sql",
                "preservation/014-weekly-ranking-snapshots.sql", "ranking/purchase-input-probe.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
        }
        // Another snapshot contains product 6: an omitted snapshot predicate must be observable.
        try (var context = start()) {
            freeze(context, UUID.randomUUID(), PERIOD.minusWeeks(1));
        }
    }

    @AfterAll
    void closePrivateDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReaderKind.class)
    @DisplayName("상품 7·8과 위치2를 commit하고 9 직전 실패 뒤 새 Spring 구성에서 9만 이어 쓴다")
    void restartsAfterCommittedChunk(ReaderKind kind) throws Exception {
        verifyRestart(kind, FailurePoint.BEFORE_NINE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReaderKind.class)
    @DisplayName("9의 INSERT와 checkpoint UPDATE 뒤 commit 직전 실패하면 함께 rollback하고 새 구성에서 복원한다")
    void rollsBackWriterAndCheckpointBeforeCommit(ReaderKind kind) throws Exception {
        verifyRestart(kind, FailurePoint.BEFORE_COMMIT);
    }

    private void verifyRestart(ReaderKind kind, FailurePoint failure) throws Exception {
        // arrange: freeze exactly once, then preserve the database across both launches.
        var snapshot = UUID.randomUUID();
        var parameters = new JobParametersBuilder().addString("snapshotId", snapshot.toString(), true).toJobParameters();
        JobExecution first;
        PurchaseInputProbeJob.Probe previousProbe;
        DataSource previousDataSource;
        JobRepository previousRepository;
        JobExplorer previousExplorer;
        try (var context = start()) {
            freeze(context, snapshot, PERIOD);
            assertFrozen(snapshot);
            assertRealConfiguration(context);
            var observation = context.getBean(ProbeConnectionObservation.class);
            observation.clear();
            previousProbe = PurchaseInputProbeJob.create(context.getBean(JobRepository.class),
                context.getBean(PlatformTransactionManager.class), context.getBean(DataSource.class),
                parameters, kind, failure, observation);

            // act: the first chunk commits; the next attempt fails at the selected boundary.
            first = context.getBean(JobLauncher.class).run(previousProbe.job(), parameters);

            // assert: re-query persistence through independent connections and JobExplorer.
            assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(first.getAllFailureExceptions()).anyMatch(exception -> exception.getMessage().contains(failure.name()));
            assertThat(probeRows(snapshot)).containsExactly(7L, 8L);
            var persisted = persistedStep(context, first);
            assertCheckpoint(persisted, kind);
            // Failed-chunk reads remain in statistics; the committed reader position is still 2.
            assertThat(persisted.getReadCount()).isEqualTo(3);
            assertThat(persisted.getWriteCount()).isEqualTo(2);
            assertThat(previousProbe.processed()).containsExactly(7L, 8L, 9L);
            if (failure == FailurePoint.BEFORE_COMMIT) {
                assertThat(previousProbe.written()).containsExactly(7L, 8L, 9L);
                assertFailedTransaction(previousProbe.failedTransaction(), observation.events());
            } else {
                assertThat(previousProbe.written()).containsExactly(7L, 8L);
            }
            assertCommittedTransaction(observation.events(), kind);
            assertReaderReleased(context, previousProbe, observation);
            assertFrozen(snapshot);
            previousDataSource = context.getBean(DataSource.class);
            previousRepository = context.getBean(JobRepository.class);
            previousExplorer = context.getBean(JobExplorer.class);
            System.out.printf("C03 %s %s first instance=%d execution=%d step=%d rows=[7,8] checkpoint=%s%n",
                kind, failure, first.getJobInstance().getId(), first.getId(), persisted.getId(), persisted.getExecutionContext());
        }
        assertThat(previousDataSource.unwrap(HikariDataSource.class).isClosed()).isTrue();

        try (var restarted = start()) {
            assertThat(restarted.getBean(DataSource.class)).isNotSameAs(previousDataSource);
            assertThat(restarted.getBean(JobRepository.class)).isNotSameAs(previousRepository);
            assertThat(restarted.getBean(JobExplorer.class)).isNotSameAs(previousExplorer);
            assertThat(probeRows(snapshot)).containsExactly(7L, 8L);
            assertCheckpoint(persistedStep(restarted, first), kind);
            var observation = restarted.getBean(ProbeConnectionObservation.class);
            observation.clear();
            var resumed = PurchaseInputProbeJob.create(restarted.getBean(JobRepository.class),
                restarted.getBean(PlatformTransactionManager.class), restarted.getBean(DataSource.class),
                parameters, kind, FailurePoint.NONE, observation);
            assertThat(resumed.reader()).isNotSameAs(previousProbe.reader());
            assertThat(resumed.job()).isNotSameAs(previousProbe.job());

            var second = restarted.getBean(JobLauncher.class).run(resumed.job(), parameters);

            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(second.getJobInstance().getId()).isEqualTo(first.getJobInstance().getId());
            assertThat(second.getId()).isNotEqualTo(first.getId());
            assertThat(second.getJobParameters()).isEqualTo(first.getJobParameters());
            assertThat(resumed.processed()).containsExactly(9L);
            assertThat(resumed.written()).containsExactly(9L);
            assertThat(probeRows(snapshot)).containsExactly(7L, 8L, 9L);
            var persisted = persistedStep(restarted, second);
            assertThat(persisted.getReadCount()).isEqualTo(1);
            assertThat(persisted.getWriteCount()).isEqualTo(1);
            assertThat(persisted.getId()).isNotEqualTo(persistedStep(restarted, first).getId());
            assertReaderReleased(restarted, resumed, observation);
            assertFrozen(snapshot);
            System.out.printf("C03 %s %s resumed instance=%d execution=%d step=%d processed=%s written=%s state=%s%n",
                kind, failure, second.getJobInstance().getId(), second.getId(), persisted.getId(),
                resumed.processed(), resumed.written(), persisted.getExecutionContext());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReaderKind.class)
    @DisplayName("빈 FROZEN 입력은 다른 snapshot 상품을 읽지 않고 0건으로 정상 완료한다")
    void completesEmptySnapshotWithoutForeignProducts(ReaderKind kind) throws Exception {
        var snapshot = UUID.randomUUID();
        try (var context = start()) {
            freeze(context, snapshot, PERIOD.plusWeeks(1));
            var parameters = new JobParametersBuilder().addString("snapshotId", snapshot.toString(), true).toJobParameters();
            var observation = context.getBean(ProbeConnectionObservation.class);
            observation.clear();
            var probe = PurchaseInputProbeJob.create(context.getBean(JobRepository.class),
                context.getBean(PlatformTransactionManager.class), context.getBean(DataSource.class),
                parameters, kind, FailurePoint.NONE, observation);

            var execution = context.getBean(JobLauncher.class).run(probe.job(), parameters);

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(probe.processed()).isEmpty();
            assertThat(probe.written()).isEmpty();
            assertThat(probeRows(snapshot)).isEmpty();
            var persisted = persistedStep(context, execution);
            assertThat(persisted.getReadCount()).isZero();
            assertThat(persisted.getWriteCount()).isZero();
            assertReaderReleased(context, probe, observation);
        }
    }

    private ConfigurableApplicationContext start() {
        return PreservationContexts.start(ProbeConfiguration.class, database.arguments());
    }

    private void freeze(ConfigurableApplicationContext context, UUID snapshot, LocalDate period) {
        context.getBean(FreezeRankingSnapshotService.class).freeze(new FreezeRankingSnapshotCommand(snapshot, period, ++revision));
    }

    private void assertRealConfiguration(ConfigurableApplicationContext context) throws SQLException {
        var source = context.getBean(DataSource.class);
        var manager = context.getBean(PlatformTransactionManager.class);
        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) manager).getDataSource()).isSameAs(source);
        assertThat(AopTestUtils.<Object>getUltimateTargetObject(context.getBean(JobRepository.class)))
            .isInstanceOf(SimpleJobRepository.class);
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("ranking-preserve");
        assertThat(context.getEnvironment().getProperty("spring.batch.job.enabled")).isEqualTo("false");
        try (var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(database.jdbcUrl());
        }
    }

    private StepExecution persistedStep(ConfigurableApplicationContext context, JobExecution execution) {
        var step = execution.getStepExecutions().iterator().next();
        var persisted = context.getBean(JobExplorer.class).getStepExecution(execution.getId(), step.getId());
        assertThat(persisted).isNotNull();
        return persisted;
    }

    private void assertCheckpoint(StepExecution step, ReaderKind kind) {
        var state = step.getExecutionContext();
        assertThat(state.getInt("purchaseProductProbe.read.count")).isEqualTo(2);
        if (kind == ReaderKind.PAGING) {
            assertThat(state.get("purchaseProductProbe.start.after")).isEqualTo(Map.of("product_id", 8L));
        }
    }

    private List<Long> probeRows(UUID snapshot) throws SQLException {
        var rows = new ArrayList<Long>();
        try (var connection = database.connect(); var statement = connection.prepareStatement(
            "SELECT product_id FROM batch_restart_probe WHERE snapshot_id=? ORDER BY product_id")) {
            statement.setString(1, snapshot.toString());
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(result.getLong(1));
                }
            }
        }
        return rows;
    }

    private void assertFrozen(UUID snapshot) throws Exception {
        var canonical = new StringBuilder();
        try (var connection = database.connect()) {
            try (var manifest = connection.prepareStatement(
                "SELECT state,input_count,input_checksum FROM weekly_ranking_snapshots WHERE snapshot_id=?")) {
                manifest.setString(1, snapshot.toString());
                try (var result = manifest.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("FROZEN");
                    assertThat(result.getLong(2)).isEqualTo(4);
                    assertThat(result.getString(3)).isEqualTo(FIXTURE_HASH);
                }
            }
            try (var inputs = connection.prepareStatement("""
                SELECT order_id,product_id,quantity,DATE_FORMAT(confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ')
                FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY order_id,product_id
                """)) {
                inputs.setString(1, snapshot.toString());
                try (var result = inputs.executeQuery()) {
                    while (result.next()) {
                        canonical.append(result.getLong(1)).append('\t').append(result.getLong(2)).append('\t')
                            .append(result.getInt(3)).append('\t').append(result.getString(4)).append('\n');
                    }
                }
            }
            try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT COUNT(*) FROM product_purchase_activities")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(5);
            }
        }
        assertThat(canonical.toString()).isEqualTo("""
            42\t7\t2\t2026-08-17T01:00:00.000000Z
            42\t8\t1\t2026-08-17T01:00:00.000000Z
            43\t7\t3\t2026-08-18T01:00:00.000000Z
            44\t9\t5\t2026-08-19T01:00:00.000000Z
            """);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)))).isEqualTo(FIXTURE_HASH);
    }

    private void assertCommittedTransaction(List<ProbeConnectionObservation.Event> events, ReaderKind kind) {
        var writer = events.stream().filter(event -> event.sql().startsWith("INSERT INTO batch_restart_probe"))
            .findFirst().orElseThrow();
        assertThat(writer.autoCommit()).isFalse();
        assertThat(events).anyMatch(event -> event.connectionId() == writer.connectionId() && !event.autoCommit()
            && event.sql().startsWith("UPDATE BATCH_STEP_EXECUTION_CONTEXT"));
        assertThat(events).anyMatch(event -> event.connectionId() == writer.connectionId() && event.operation().equals("commit"));
        if (kind == ReaderKind.CURSOR) {
            var cursor = events.stream().filter(event -> event.sql().startsWith("SELECT DISTINCT product_id"))
                .findFirst().orElseThrow();
            assertThat(cursor.connectionId()).isNotEqualTo(writer.connectionId());
        }
        System.out.printf("C03 %s chunk commit writer+metadata connection=%d autoCommit=false%n", kind, writer.connectionId());
    }

    private void assertFailedTransaction(List<ProbeConnectionObservation.Event> beforeCommit,
                                         List<ProbeConnectionObservation.Event> all) {
        var inserts = beforeCommit.stream().filter(event -> event.sql().startsWith("INSERT INTO batch_restart_probe")).toList();
        assertThat(inserts).hasSize(1);
        var writer = inserts.getFirst();
        assertThat(writer.autoCommit()).isFalse();
        var updates = beforeCommit.stream().filter(event -> event.sql().startsWith("UPDATE BATCH_STEP_EXECUTION_CONTEXT")).toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst().connectionId()).isEqualTo(writer.connectionId());
        assertThat(updates.getFirst().autoCommit()).isFalse();
        assertThat(beforeCommit.indexOf(updates.getFirst())).isGreaterThan(beforeCommit.indexOf(writer));
        assertThat(beforeCommit).noneMatch(event -> event.operation().equals("commit"));
        assertThat(all).anyMatch(event -> event.connectionId() == writer.connectionId() && event.operation().equals("rollback"));
        assertThat(all).noneMatch(event -> event.connectionId() == writer.connectionId() && event.operation().equals("commit"));
        System.out.printf("C03 rollback connection=%d writer9+checkpoint SQL completed before beforeCommit failure%n",
            writer.connectionId());
    }

    private void assertReaderReleased(ConfigurableApplicationContext context, PurchaseInputProbeJob.Probe probe,
                                      ProbeConnectionObservation observation) throws SQLException {
        // The connection pool must have no borrowed reader/writer connection after synchronous launch returns.
        assertThat(context.getBean(DataSource.class).unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections())
            .isZero();
        assertThat(probe.reader()).isNotNull();
        assertThat(probe.reader().opens()).isEqualTo(1);
        assertThat(probe.reader().closes()).isEqualTo(1);
        var cursor = observation.events().stream()
            .filter(event -> event.sql().startsWith("SELECT DISTINCT product_id"))
            .findFirst();
        if (cursor.isPresent()) {
            assertThat(observation.events()).anyMatch(event -> event.operation().equals("close")
                && event.connectionId() == cursor.get().connectionId());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({"com.loopers.config.jpa", "com.loopers.application.ranking", "com.loopers.infrastructure.persistence.ranking"})
    static class ProbeConfiguration {
        @Bean
        static ProbeConnectionObservation probeConnectionObservation() {
            return new ProbeConnectionObservation();
        }
    }
}
