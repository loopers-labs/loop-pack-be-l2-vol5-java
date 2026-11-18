package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.loopers.application.ranking.command.FreezeRankingSnapshotCommand;
import com.loopers.application.ranking.result.FrozenRankingSnapshotResult;
import com.loopers.application.ranking.service.FreezeRankingSnapshotService;
import com.loopers.application.ranking.support.SnapshotFreezeRetryableException;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.support.preservation.PreservedMySql;
import com.loopers.support.preservation.PreservationContexts;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RankingSnapshotFreezeTest {
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 17);
    private static final String FIXTURE_HASH = "3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298";
    private PreservedMySql database;
    private ConfigurableApplicationContext context;
    private FreezeRankingSnapshotService service;
    private RankingSnapshotTestConfiguration.Control control;
    private JdbcTemplate jdbc;

    @BeforeAll
    void preparePrivateDatabase() throws Exception {
        database = new PreservedMySql();
        database.prepareCommerceSchema();
        try (var connection = database.connect()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/001-batch-metadata-mysql.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("preservation/014-weekly-ranking-snapshots.sql"));
        }
        openContext();
    }

    private void openContext() {
        context = PreservationContexts.start(RankingSnapshotTestConfiguration.class, database.arguments());
        service = context.getBean(FreezeRankingSnapshotService.class);
        control = context.getBean(RankingSnapshotTestConfiguration.Control.class);
        jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("ranking-preserve");
        assertThat(context.getEnvironment().getProperty("datasource.mysql-jpa.main.jdbc-url")).isEqualTo(database.jdbcUrl());
    }

    @BeforeEach
    void clearOnlyPrivateFixtureData() throws Exception {
        control.reset();
        try (var connection = database.connect(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS c02_fail_snapshot_copy");
            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM weekly_ranking_snapshot_inputs");
            statement.executeUpdate("DELETE FROM weekly_ranking_snapshots");
            statement.executeUpdate("DELETE FROM product_purchase_activities");
            statement.executeUpdate("DELETE FROM order_confirmation_inbox");
            connection.commit();
        }
    }

    @AfterEach
    void resetControls() {
        control.reset();
    }

    @AfterAll
    void closePrivateResources() {
        if (context != null) {
            context.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("실제 다품목 4행을 단일 복사하고 동일 TX에서 건수·checksum·DB 고정 완료 시각을 저장한다")
    void freezesCanonicalFourRows() throws Exception {
        seedCanonicalInput();
        var before = dbNow();

        var result = freeze(SNAPSHOT, 1);

        assertThat(result.inputCount()).isEqualTo(4);
        assertThat(result.inputChecksum()).isEqualTo(FIXTURE_HASH);
        assertThat(result.periodStart()).isEqualTo(PERIOD);
        assertThat(result.periodEnd()).isEqualTo(PERIOD.plusDays(7));
        assertThat(result.frozenAt()).isBetween(before, dbNow());
        assertThat(control.isolation).isEqualTo("READ-COMMITTED");
        assertThat(rows(SNAPSHOT)).extracting(InputRow::orderId, InputRow::productId, InputRow::quantity, InputRow::confirmedAt)
            .containsExactly(
                tuple(42L, 7L, 2, Instant.parse("2026-08-17T01:00:00Z")),
                tuple(42L, 8L, 1, Instant.parse("2026-08-17T01:00:00Z")),
                tuple(43L, 7L, 3, Instant.parse("2026-08-18T01:00:00Z")),
                tuple(44L, 9L, 5, Instant.parse("2026-08-19T01:00:00Z"))
            );
        assertThat(jdbc.queryForMap("SELECT state, input_contract_version FROM weekly_ranking_snapshots WHERE snapshot_id=?",
            SNAPSHOT.toString())).containsEntry("state", "FROZEN").containsEntry("input_contract_version", 1);
        assertThat(sourceCount()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul"})
    @DisplayName("실제 MySQL 복사·재조회에서 UTC 양끝과 마이크로초를 JVM 기본 시간대와 무관하게 유지한다")
    void preservesUtcBoundariesAndMicroseconds(String timezone) throws Exception {
        var previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(timezone));
            source(40, 7, 1, "2026-08-16T23:59:59.999999Z");
            source(41, 7, 2, "2026-08-17T00:00:00Z");
            source(42, 8, 3, "2026-08-23T23:59:59.999999Z");
            source(43, 9, 4, "2026-08-24T00:00:00Z");

            assertThat(freeze(SNAPSHOT, 1).inputCount()).isEqualTo(2);
            assertThat(rows(SNAPSHOT)).extracting(InputRow::orderId, InputRow::confirmedAt)
                .containsExactly(tuple(41L, Instant.parse("2026-08-17T00:00:00Z")),
                    tuple(42L, Instant.parse("2026-08-23T23:59:59.999999Z")));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    @DisplayName("빈 입력도 FROZEN manifest와 빈 checksum으로 저장한다")
    void freezesAnEmptyWeek() {
        var result = freeze(SNAPSHOT, 1);

        assertThat(result.inputCount()).isZero();
        assertThat(result.inputChecksum()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(jdbc.queryForObject("SELECT state FROM weekly_ranking_snapshots WHERE snapshot_id=?", String.class,
            SNAPSHOT.toString())).isEqualTo("FROZEN");
    }

    @Test
    @DisplayName("같은 snapshot은 늦은 원본을 다시 복사하지 않고 재검증·재사용하며 새 revision에만 추가 구매를 반영한다")
    void reusesFrozenInputAndRecalculatesOnlyWithNewRevision() throws Exception {
        seedCanonicalInput();
        var original = freeze(SNAPSHOT, 1);
        source(45, 9, 1, "2026-08-20T01:00:00Z");

        assertThat(freeze(SNAPSHOT, 1)).isEqualTo(original);
        assertThat(rows(SNAPSHOT)).hasSize(4);
        assertThat(freeze(OTHER, 2).inputCount()).isEqualTo(5);
        assertThat(rows(OTHER)).hasSize(5);
        assertThat(sourceCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("동일 ID의 다른 기간·revision과 다른 ID의 동일 기간·revision은 기존 입력을 덮어쓰지 않는다")
    void rejectsConflictingIdentities() throws Exception {
        seedCanonicalInput();
        var original = freeze(SNAPSHOT, 1);

        assertThatThrownBy(() -> service.freeze(new FreezeRankingSnapshotCommand(SNAPSHOT, PERIOD.plusDays(7), 1)))
            .isInstanceOf(SnapshotConflictException.class);
        assertThatThrownBy(() -> service.freeze(new FreezeRankingSnapshotCommand(SNAPSHOT, PERIOD, 2)))
            .isInstanceOf(SnapshotConflictException.class);
        assertThatThrownBy(() -> service.freeze(new FreezeRankingSnapshotCommand(OTHER, PERIOD, 1)))
            .isInstanceOf(SnapshotConflictException.class);
        assertThat(freeze(SNAPSHOT, 1)).isEqualTo(original);
        assertThat(manifestCount()).isEqualTo(1);
        assertThat(rows(OTHER)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "missing-row", "timestamp", "manifest-count", "contract-version"})
    @DisplayName("사본·건수·입력 계약의 변조는 원본 재복사로 수리하거나 정상 재사용으로 숨기지 않는다")
    void rejectsChangedFrozenEvidence(String corruption) throws Exception {
        seedCanonicalInput();
        freeze(SNAPSHOT, 1);
        switch (corruption) {
            case "quantity" -> jdbc.update("UPDATE weekly_ranking_snapshot_inputs SET quantity=99 WHERE snapshot_id=? AND order_id=43",
                SNAPSHOT.toString());
            case "missing-row" -> jdbc.update("DELETE FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? AND order_id=43",
                SNAPSHOT.toString());
            case "timestamp" -> jdbc.update("UPDATE weekly_ranking_snapshot_inputs SET confirmed_at='2026-08-24' WHERE snapshot_id=? AND order_id=43",
                SNAPSHOT.toString());
            case "manifest-count" -> jdbc.update("UPDATE weekly_ranking_snapshots SET input_count=5 WHERE snapshot_id=?", SNAPSHOT.toString());
            case "contract-version" -> jdbc.update("UPDATE weekly_ranking_snapshots SET input_contract_version=2 WHERE snapshot_id=?", SNAPSHOT.toString());
            default -> throw new IllegalArgumentException(corruption);
        }

        assertThatThrownBy(() -> service.freeze(command(SNAPSHOT, 1)))
            .isInstanceOf(corruption.equals("contract-version") ? SnapshotConflictException.class : SnapshotIntegrityException.class);
        assertThat(sourceCount()).isEqualTo(4);
        assertThat(control.copies).hasValue(1);
        if (corruption.equals("quantity")) {
            assertThat(rows(SNAPSHOT)).filteredOn(row -> row.orderId() == 43).extracting(InputRow::quantity).containsExactly(99);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("복사 후 또는 FROZEN 갱신 후 실패하면 manifest·입력 전체가 rollback된다")
    void rollsBackWholeFreezeAfterCopyOrSeal(boolean afterSeal) throws Exception {
        seedCanonicalInput();
        Runnable fail = () -> {
            control.rowsBeforeFailure = jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshot_inputs", Long.class);
            throw new IllegalStateException("c02 injected failure");
        };
        if (afterSeal) {
            control.afterComplete = fail;
        } else {
            control.beforeComplete = fail;
        }

        assertThatThrownBy(() -> service.freeze(command(SNAPSHOT, 1)))
            .isInstanceOf(IllegalStateException.class).hasMessage("c02 injected failure");
        assertThat(control.rowsBeforeFailure).isEqualTo(4);
        assertNoFrozenData(SNAPSHOT);
        assertThat(sourceCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("사본 checksum 읽기 중 실패하면 전체 rollback하고 같은 요청을 새로 고정할 수 있다")
    void rollsBackWhenStreamingChecksumRejectsCopiedInput() throws Exception {
        seedCanonicalInput();
        control.afterCopy = () -> jdbc.update("""
            UPDATE weekly_ranking_snapshot_inputs SET confirmed_at='2026-08-24'
            WHERE snapshot_id=? AND order_id=43
            """, SNAPSHOT.toString());

        assertThatThrownBy(() -> freeze(SNAPSHOT, 1)).isInstanceOf(SnapshotIntegrityException.class);

        assertNoFrozenData(SNAPSHOT);
        assertThat(sourceCount()).isEqualTo(4);
        control.afterCopy = () -> {};
        var retried = freeze(SNAPSHOT, 1);
        assertThat(retried.inputCount()).isEqualTo(4);
        assertThat(retried.inputChecksum()).isEqualTo(FIXTURE_HASH);
        assertThat(rows(SNAPSHOT)).hasSize(4);
    }

    @Test
    @DisplayName("복사 SQL 도중 실제 DB 오류가 발생하면 초안 manifest도 rollback된다")
    void rollsBackWhenCopySqlFailsMidStatement() throws Exception {
        seedCanonicalInput();
        try (var connection = database.connectAsAdministrator(); var statement = connection.createStatement()) {
            statement.execute("""
            CREATE TRIGGER c02_fail_snapshot_copy BEFORE INSERT ON weekly_ranking_snapshot_inputs
            FOR EACH ROW BEGIN
              IF NEW.order_id = 43 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'c02 copy failure'; END IF;
            END
            """);
        }

        assertThatThrownBy(() -> service.freeze(command(SNAPSHOT, 1))).hasStackTraceContaining("c02 copy failure");
        assertNoFrozenData(SNAPSHOT);
        assertThat(sourceCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("입력 PK 오류를 manifest 예약 중복으로 오인하여 재사용하지 않는다")
    void doesNotTreatInputDuplicateAsManifestDuplicate() throws Exception {
        seedCanonicalInput();
        control.afterReserve = () -> jdbc.update("""
            INSERT INTO weekly_ranking_snapshot_inputs (snapshot_id, order_id, product_id, quantity, confirmed_at)
            VALUES (?,42,7,2,'2026-08-17 01:00:00')
            """, SNAPSHOT.toString());

        assertThatThrownBy(() -> service.freeze(command(SNAPSHOT, 1))).isInstanceOf(DuplicateKeyException.class);
        assertNoFrozenData(SNAPSHOT);
    }

    @Test
    @DisplayName("uncommitted 낮은 ID와 복사 뒤 도착한 활동은 제외하고 새 revision에서만 포함한다")
    void excludesUncommittedAndLateRowsAtCopyReadPoint() throws Exception {
        seedCanonicalInput();
        var copied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        control.afterCopy = () -> {
            copied.countDown();
            await(release);
        };
        try (var pending = database.connect(); var tasks = Executors.newSingleThreadExecutor()) {
            pending.setAutoCommit(false);
            source(pending, 45, 9, 1, Instant.parse("2026-08-20T01:00:00Z"));
            long pendingId = activityId(pending, 45);
            source(46, 9, 2, "2026-08-20T02:00:00Z");
            try (var connection = database.connect()) {
                assertThat(pendingId).isLessThan(activityId(connection, 46));
            }
            var first = tasks.submit(() -> freeze(SNAPSHOT, 1));
            try {
                assertThat(copied.await(10, TimeUnit.SECONDS)).isTrue();
                pending.commit();
                source(47, 9, 3, "2026-08-20T03:00:00Z");
                var lateCommitObserved = dbNow();
                release.countDown();
                var result = first.get(10, TimeUnit.SECONDS);
                assertThat(result.inputCount()).isEqualTo(5);
                assertThat(result.frozenAt()).isAfterOrEqualTo(lateCommitObserved);
                assertThat(rows(SNAPSHOT)).extracting(InputRow::orderId).contains(46L).doesNotContain(45L, 47L);
                control.afterCopy = () -> {};
                assertThat(freeze(SNAPSHOT, 1)).isEqualTo(result);
                assertThat(freeze(OTHER, 2).inputCount()).isEqualTo(7);
            } finally {
                pending.commit();
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("동시 예약의 정상 중복은 새 TX에서 같은 결과를 재사용하고 다른 ID의 기간 점유는 충돌한다")
    void resolvesConcurrentReservationsAfterRollback(boolean sameId) throws Exception {
        seedCanonicalInput();
        var copied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reservations = new CountDownLatch(2);
        control.beforeReserve = reservations::countDown;
        control.afterCopy = () -> {
            copied.countDown();
            await(release);
        };
        try (var tasks = Executors.newFixedThreadPool(2)) {
            var first = tasks.submit(() -> freeze(SNAPSHOT, 1));
            try {
                assertThat(copied.await(10, TimeUnit.SECONDS)).isTrue();
                var second = tasks.submit(() -> service.freeze(command(sameId ? SNAPSHOT : OTHER, 1)));
                assertThat(reservations.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                var original = first.get(10, TimeUnit.SECONDS);
                if (sameId) {
                    assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(original);
                } else {
                    assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(SnapshotConflictException.class);
                }
                assertThat(manifestCount()).isEqualTo(1);
                assertThat(rows(SNAPSHOT)).hasSize(4);
                assertThat(control.copies).hasValue(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("실제 예약 lock timeout은 전체 rollback하고 자동 재시도 없이 같은 요청의 명시 재시도를 허용한다")
    void rollsBackLockTimeoutAndAllowsCallerRetry() throws Exception {
        seedCanonicalInput();
        var copied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restored = new AtomicBoolean();
        control.beforeReserve = () -> {
            if (control.reserves.get() == 2) {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");
                        restored.set(status == STATUS_ROLLED_BACK);
                    }
                });
            }
        };
        control.afterCopy = () -> {
            copied.countDown();
            await(release);
        };
        try (var tasks = Executors.newFixedThreadPool(2)) {
            var first = tasks.submit(() -> freeze(SNAPSHOT, 1));
            try {
                assertThat(copied.await(10, TimeUnit.SECONDS)).isTrue();
                var second = tasks.submit(() -> service.freeze(command(SNAPSHOT, 1)));
                assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(SnapshotFreezeRetryableException.class);
                assertThat(restored).isTrue();
                assertThat(control.reserves).hasValue(2);
                release.countDown();
                var original = first.get(10, TimeUnit.SECONDS);
                assertThat(freeze(SNAPSHOT, 1)).isEqualTo(original);
                assertThat(manifestCount()).isEqualTo(1);
                assertThat(control.copies).hasValue(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("호출자의 RR TX가 rollback해도 독립 RC freeze의 완성 입력은 보존된다")
    void suspendsOuterTransactionForIndependentFreeze() throws Exception {
        seedCanonicalInput();
        var outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO order_confirmation_inbox
                  (id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
                VALUES (999,?,999,'OrderConfirmed',1,'2026-08-17','{}','2026-08-17')
                """, new UUID(0, 999).toString());
            freeze(SNAPSHOT, 1);
            assertThat(control.isolation).isEqualTo("READ-COMMITTED");
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            status.setRollbackOnly();
        });

        assertThat(rows(SNAPSHOT)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_confirmation_inbox WHERE order_id=999", Long.class)).isZero();
    }

    @Test
    @DisplayName("새 application context에서도 기존 snapshot을 재검증하고 같은 manifest로 반환한다")
    void reusesSnapshotAfterContextRecreation() throws Exception {
        seedCanonicalInput();
        var original = freeze(SNAPSHOT, 1);
        var oldService = service;
        context.close();
        openContext();

        assertThat(service).isNotSameAs(oldService);
        assertThat(freeze(SNAPSHOT, 1)).isEqualTo(original);
        assertThat(rows(SNAPSHOT)).hasSize(4);
    }

    @Test
    @DisplayName("잘못된 snapshot 요청은 DB 저장 전에 거절한다")
    void rejectsInvalidCommandsWithoutWriting() {
        assertThatThrownBy(() -> service.freeze(new FreezeRankingSnapshotCommand(null, PERIOD, 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.freeze(new FreezeRankingSnapshotCommand(SNAPSHOT, PERIOD, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.freeze(new FreezeRankingSnapshotCommand(SNAPSHOT, PERIOD.plusDays(1), 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(manifestCount()).isZero();
    }

    private FreezeRankingSnapshotCommand command(UUID id, int revision) {
        return new FreezeRankingSnapshotCommand(id, PERIOD, revision);
    }

    private FrozenRankingSnapshotResult freeze(UUID id, int revision) {
        var result = service.freeze(command(id, revision));
        assertThat(result).as("a committed frozen manifest must be returned").isNotNull();
        return result;
    }

    private void seedCanonicalInput() throws SQLException {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            source(connection, 42, 7, 2, Instant.parse("2026-08-17T01:00:00Z"));
            source(connection, 42, 8, 1, Instant.parse("2026-08-17T01:00:00Z"));
            source(connection, 43, 7, 3, Instant.parse("2026-08-18T01:00:00Z"));
            source(connection, 44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"));
            connection.commit();
        }
    }

    private void source(long orderId, long productId, int quantity, String confirmedAt) throws SQLException {
        try (var connection = database.connect()) {
            connection.setAutoCommit(false);
            source(connection, orderId, productId, quantity, Instant.parse(confirmedAt));
            connection.commit();
        }
    }

    private void source(Connection connection, long orderId, long productId, int quantity, Instant confirmedAt) throws SQLException {
        var utc = LocalDateTime.ofInstant(confirmedAt, ZoneOffset.UTC);
        boolean hasInbox;
        try (var lookup = connection.prepareStatement("SELECT id FROM order_confirmation_inbox WHERE order_id=?")) {
            lookup.setLong(1, orderId);
            try (var result = lookup.executeQuery()) {
                hasInbox = result.next();
            }
        }
        if (!hasInbox) {
            try (var inbox = connection.prepareStatement("""
            INSERT INTO order_confirmation_inbox
              (id,event_id,order_id,event_type,schema_version,confirmed_at,normalized_payload,processed_at)
            VALUES (?,?,?,'OrderConfirmed',1,?,'{}',?)
            """)) {
            inbox.setLong(1, orderId);
            inbox.setString(2, new UUID(0, orderId).toString());
            inbox.setLong(3, orderId);
            inbox.setObject(4, utc);
            inbox.setObject(5, utc);
            inbox.executeUpdate();
            }
        }
        try (var row = connection.prepareStatement("""
            INSERT INTO product_purchase_activities (inbox_id,order_id,product_id,quantity,confirmed_at) VALUES (?,?,?,?,?)
            """)) {
            row.setLong(1, orderId);
            row.setLong(2, orderId);
            row.setLong(3, productId);
            row.setInt(4, quantity);
            row.setObject(5, utc);
            row.executeUpdate();
        }
    }

    private long activityId(Connection connection, long orderId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM product_purchase_activities WHERE order_id=?")) {
            statement.setLong(1, orderId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private List<InputRow> rows(UUID snapshotId) throws SQLException {
        var rows = new ArrayList<InputRow>();
        try (var connection = database.connect(); var statement = connection.prepareStatement("""
            SELECT order_id,product_id,quantity,confirmed_at FROM weekly_ranking_snapshot_inputs
            WHERE snapshot_id=? ORDER BY order_id,product_id
            """)) {
            statement.setString(1, snapshotId.toString());
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new InputRow(result.getLong(1), result.getLong(2), result.getInt(3),
                        result.getObject(4, LocalDateTime.class).toInstant(ZoneOffset.UTC)));
                }
            }
        }
        return rows;
    }

    private Instant dbNow() throws SQLException {
        try (var connection = database.connect(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT UTC_TIMESTAMP(6)")) {
            assertThat(result.next()).isTrue();
            return result.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC);
        }
    }

    private long manifestCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshots", Long.class);
    }

    private long sourceCount() throws SQLException {
        try (var connection = database.connect(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM product_purchase_activities")) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private void assertNoFrozenData(UUID id) throws SQLException {
        assertThat(manifestCount()).isZero();
        assertThat(rows(id)).isEmpty();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("c02 test coordination timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record InputRow(long orderId, long productId, int quantity, Instant confirmedAt) {}
}
