package com.loopers.application.ranking;

import com.loopers.domain.ranking.FrozenRankingInput;
import com.loopers.domain.ranking.RankingPurchase;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import com.loopers.domain.ranking.RankingSnapshotStore;
import com.loopers.domain.ranking.WeeklyRankingKey;
import com.loopers.infrastructure.ranking.JdbcRankingSnapshotStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Manual DDL and independent committed reads use only this class's MySQL container. */
@SpringJUnitConfig(WeeklyRankingSnapshotIntegrationTest.Config.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class WeeklyRankingSnapshotIntegrationTest {
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 17);
    private static final Instant FROZEN_AT = Instant.parse("2026-08-24T00:30:00.987654321Z");
    // Independently fixed SHA-256 of the four documented TAB/LF UTF-8 rows, with nine-digit UTC nanos.
    private static final String FIXTURE_CHECKSUM = "4a00f7d7a6421c8e457a59012754f53ca6069dc391e14c582fc7e551bab60863";
    private static final String EMPTY_CHECKSUM = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_ranking_snapshot_test");

    @Autowired private HikariDataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcTransactionManager transactionManager;
    @Autowired private RankingSnapshotStore store;
    @Autowired private WeeklyRankingSnapshotService service;
    private WeeklyRankingKey key;
    private boolean workersTerminated = true;

    @BeforeEach
    void setKey() {
        key = new WeeklyRankingKey(PERIOD_START, 1);
    }

    @AfterEach
    void cleanOnlyOwnedRowsAfterWorkersFinish() {
        assertThat(workersTerminated).isTrue();
        reset(store);
        jdbc.update("DELETE FROM weekly_ranking_input");
        jdbc.update("DELETE FROM weekly_ranking_snapshot");
        jdbc.update("DELETE FROM product_purchase_activity");
        jdbc.update("DELETE FROM processed_order_confirmation");
    }

    @Test
    void freezesAllFourDocumentedItemsAndCommitsAnExactManifest() {
        seed(jdbc, fixture());
        var original = sourceRows();

        FrozenRankingInput frozen = service.freeze(key);

        assertThat(frozen.key()).isEqualTo(key);
        assertThat(frozen.purchases()).containsExactlyElementsOf(fixture());
        assertThat(frozen.frozenAt()).isEqualTo(FROZEN_AT);
        assertThat(frozen.inputCount()).isEqualTo(4);
        assertThat(frozen.productCount()).isEqualTo(3);
        assertThat(frozen.checksumVersion()).isEqualTo("v1");
        assertThat(frozen.inputChecksum()).isEqualTo(FIXTURE_CHECKSUM);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertSnapshotCounts(1, 4);
        assertThat(readInputs(key)).containsExactlyElementsOf(fixture());
        assertThat(jdbc.queryForObject("SELECT input_checksum FROM weekly_ranking_snapshot", String.class))
            .isEqualTo(FIXTURE_CHECKSUM);
        assertThat(jdbc.queryForObject("SELECT input_count FROM weekly_ranking_snapshot", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT product_count FROM weekly_ranking_snapshot", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT input_state FROM weekly_ranking_snapshot", String.class)).isEqualTo("FROZEN");
        assertThat(jdbc.queryForObject("SELECT frozen_at_second FROM weekly_ranking_snapshot", Long.class))
            .isEqualTo(FROZEN_AT.getEpochSecond());
        assertThat(jdbc.queryForObject("SELECT frozen_at_nano FROM weekly_ranking_snapshot", Integer.class))
            .isEqualTo(FROZEN_AT.getNano());
        assertThat(sourceRows()).isEqualTo(original);
    }

    @Test
    void utcWeekIncludesTheFirstInstantAndLastNanosecondAndExcludesBothAdjacentInstants() {
        Instant start = Instant.parse("2026-08-17T00:00:00Z");
        Instant end = Instant.parse("2026-08-24T00:00:00Z");
        RankingPurchase before = new RankingPurchase(40, 7, 1, start.minusNanos(1));
        RankingPurchase first = new RankingPurchase(41, 7, 2, start);
        RankingPurchase last = new RankingPurchase(42, 8, 3, end.minusNanos(1));
        RankingPurchase after = new RankingPurchase(43, 9, 4, end);
        seed(jdbc, List.of(before, first, last, after));
        var original = sourceRows();

        FrozenRankingInput frozen = service.freeze(key);

        assertThat(frozen.purchases()).containsExactly(first, last);
        assertThat(readInputs(key)).containsExactly(first, last);
        assertThat(jdbc.queryForObject("SELECT confirmed_at_nano FROM weekly_ranking_input WHERE order_id=42", Integer.class))
            .isEqualTo(999999999);
        assertThat(sourceRows()).isEqualTo(original);
        assertSnapshotCounts(1, 2);
    }

    @Test
    void emptyWeekCommitsAReusableFrozenManifestWithTheIndependentEmptyChecksum() {
        FrozenRankingInput frozen = service.freeze(key);

        assertThat(frozen.purchases()).isEmpty();
        assertThat(frozen.inputCount()).isZero();
        assertThat(frozen.productCount()).isZero();
        assertThat(frozen.inputChecksum()).isEqualTo(EMPTY_CHECKSUM);
        assertSnapshotCounts(1, 0);
        clearInvocations(store);
        assertThat(service.freeze(key)).isEqualTo(frozen);
        verify(store, never()).readSourcePage(any(), nullable(RankingPurchase.class), anyInt());
        verify(store, never()).insertManifest(any());
        verify(store, never()).insertInputs(any(), anyList());
        assertSnapshotCounts(1, 0);
    }

    @Test
    void sameKeyReusesStoredInputWithoutReadingLateSourceAndNewRevisionIncludesIt() {
        seed(jdbc, fixture());
        FrozenRankingInput first = service.freeze(key);
        var originalManifest = manifestRows();
        var originalInputs = inputRows();
        RankingPurchase late = new RankingPurchase(45, 7, 4, Instant.parse("2026-08-20T01:00:00.123456789Z"));
        seed(jdbc, List.of(late));
        clearInvocations(store);

        assertThat(service.freeze(key)).isEqualTo(first);

        verify(store, never()).readSourcePage(any(), nullable(RankingPurchase.class), anyInt());
        verify(store, never()).insertManifest(any());
        verify(store, never()).insertInputs(any(), anyList());
        assertThat(manifestRows()).isEqualTo(originalManifest);
        assertThat(inputRows()).isEqualTo(originalInputs);
        FrozenRankingInput second = service.freeze(new WeeklyRankingKey(PERIOD_START, 2));
        assertThat(second.key().snapshotId()).isNotEqualTo(first.key().snapshotId());
        assertThat(second.purchases()).containsExactly(fixture().get(0), fixture().get(1), fixture().get(2), fixture().get(3), late);
        assertThat(second.inputChecksum()).isNotEqualTo(first.inputChecksum());
        assertThat(readInputs(key)).containsExactlyElementsOf(fixture());
        assertSnapshotCounts(2, 9);
    }

    @Test
    void failureAfterFirstRealInputBatchRollsBackBothTablesAndPreservesSourceForRetry() {
        seed(jdbc, fixture());
        var original = sourceRows();
        AtomicBoolean sqlObserved = new AtomicBoolean();
        TestWriteFailure injected = new TestWriteFailure();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertSnapshotCounts(1, 2);
            sqlObserved.set(true);
            throw injected;
        }).when(store).insertInputs(eq(key.snapshotId()), anyList());

        assertThatThrownBy(() -> service.freeze(key)).isSameAs(injected);

        assertThat(sqlObserved).isTrue();
        assertSnapshotCounts(0, 0);
        assertThat(sourceRows()).isEqualTo(original);
        reset(store);
        assertThat(service.freeze(key).inputChecksum()).isEqualTo(FIXTURE_CHECKSUM);
        assertSnapshotCounts(1, 4);
    }

    @Test
    void failureAfterActualManifestInsertLeavesNoHalfSnapshot() {
        seed(jdbc, fixture());
        var original = sourceRows();
        AtomicBoolean sqlObserved = new AtomicBoolean();
        TestWriteFailure injected = new TestWriteFailure();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshot", Long.class)).isEqualTo(1L);
            sqlObserved.set(true);
            throw injected;
        }).when(store).insertManifest(any());

        assertThatThrownBy(() -> service.freeze(key)).isSameAs(injected);

        assertThat(sqlObserved).isTrue();
        assertSnapshotCounts(0, 0);
        assertThat(sourceRows()).isEqualTo(original);
    }

    @Test
    void actualDuplicateInputSqlFailsAndRollsBackManifestAndAllInsertedRows() {
        seed(jdbc, fixture());
        var original = sourceRows();
        AtomicBoolean firstBatchStored = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertSnapshotCounts(1, 2);
            firstBatchStored.set(true);
            return invocation.callRealMethod();
        }).when(store).insertInputs(eq(key.snapshotId()), anyList());

        assertThatThrownBy(() -> service.freeze(key)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(firstBatchStored).isTrue();
        assertSnapshotCounts(0, 0);
        assertThat(sourceRows()).isEqualTo(original);
    }

    @Test
    void oneReadViewExcludesAReservedLowerIdAndNewSourceCommittedBetweenPages() throws Exception {
        List<RankingPurchase> initial = fixture().subList(0, 2);
        RankingPurchase reservedLowId = fixture().get(2);
        RankingPurchase committedHighId = fixture().get(3);
        RankingPurchase late = new RankingPurchase(45, 10, 6, Instant.parse("2026-08-20T01:00:00Z"));
        seed(jdbc, initial);
        // Race activity commits only. Precommitted FK parents avoid unrelated parent-insert locks;
        // this synthetic fixture does not claim to verify consumer confirmation/activity atomicity.
        seedConfirmation(jdbc, reservedLowId);
        seedConfirmation(jdbc, committedHighId);
        seedConfirmation(jdbc, late);
        CountDownLatch firstPageRead = new CountDownLatch(1);
        CountDownLatch continuePages = new CountDownLatch(1);
        AtomicReference<List<RankingPurchase>> capturedFirstPage = new AtomicReference<>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RankingPurchase> page = (List<RankingPurchase>) invocation.callRealMethod();
            if (invocation.getArgument(1) == null) {
                capturedFirstPage.set(page);
                firstPageRead.countDown();
                await(continuePages, "Source commits did not finish between pages");
            }
            return page;
        }).when(store).readSourcePage(eq(key), nullable(RankingPurchase.class), eq(2));
        var worker = Executors.newSingleThreadExecutor();
        workersTerminated = false;
        try (Connection lowTransaction = dataSource.getConnection()) {
            lowTransaction.setAutoCommit(false);
            JdbcTemplate lowJdbc = new JdbcTemplate(new SingleConnectionDataSource(lowTransaction, true));
            seedActivity(lowJdbc, reservedLowId);
            long lowId = lowJdbc.queryForObject("SELECT id FROM product_purchase_activity WHERE order_id=43", Long.class);
            seedActivity(jdbc, committedHighId);
            long highId = jdbc.queryForObject("SELECT id FROM product_purchase_activity WHERE order_id=44", Long.class);
            assertThat(lowId).isLessThan(highId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class)).isEqualTo(3L);

            var freezing = worker.submit(() -> service.freeze(key));
            await(firstPageRead, "Freeze did not finish its first source page");
            assertThat(capturedFirstPage.get()).containsExactlyElementsOf(initial);
            lowTransaction.commit();
            seedActivity(jdbc, late);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class)).isEqualTo(5L);
            continuePages.countDown();
            FrozenRankingInput first = freezing.get(10, TimeUnit.SECONDS);

            assertThat(first.purchases()).containsExactly(initial.get(0), initial.get(1), committedHighId);
            assertThat(readInputs(key)).containsExactlyElementsOf(first.purchases());
            assertThat(first.inputCount()).isEqualTo(3);
            reset(store);
            FrozenRankingInput second = service.freeze(new WeeklyRankingKey(PERIOD_START, 2));
            assertThat(second.purchases()).containsExactly(initial.get(0), initial.get(1), reservedLowId, committedHighId, late);
            assertThat(readInputs(key)).containsExactlyElementsOf(first.purchases());
            assertThat(jdbc.queryForObject("SELECT id FROM product_purchase_activity WHERE order_id=43", Long.class)).isEqualTo(lowId);
            assertSnapshotCounts(2, 8);
        } finally {
            continuePages.countDown();
            worker.shutdownNow();
            workersTerminated = worker.awaitTermination(5, TimeUnit.SECONDS);
            assertThat(workersTerminated).isTrue();
        }
    }

    @Test
    void participatingReadCommittedTransactionIsRejectedBeforeCreatingSnapshotRows() {
        seed(jdbc, fixture());
        var original = sourceRows();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> service.freeze(key)))
            .isInstanceOf(IllegalStateException.class);

        assertSnapshotCounts(0, 0);
        assertThat(sourceRows()).isEqualTo(original);
        verify(store, never()).readSourcePage(any(), nullable(RankingPurchase.class), anyInt());
        verify(store, never()).insertManifest(any());
    }

    @Test
    void adapterRejectsAccessWithoutATransactionBeforeConsultingJdbc() {
        seed(jdbc, fixture());
        var original = sourceRows();
        JdbcTemplate observedJdbc = spy(new JdbcTemplate(dataSource));
        JdbcRankingSnapshotStore adapter = new JdbcRankingSnapshotStore(observedJdbc);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();

        assertThatThrownBy(() -> adapter.find(key)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(observedJdbc);
        assertSnapshotCounts(0, 0);
        assertThat(sourceRows()).isEqualTo(original);
    }

    @Test
    void transactionOnAnotherDataSourceCannotEnlistAnUnownedConnectionWhileCheckingTheGuard() throws Exception {
        seed(jdbc, fixture());
        var original = sourceRows();
        try (HikariDataSource detachedSource = new HikariDataSource()) {
            detachedSource.setJdbcUrl(MYSQL.getJdbcUrl());
            detachedSource.setUsername(MYSQL.getUsername());
            detachedSource.setPassword(MYSQL.getPassword());
            detachedSource.setMaximumPoolSize(1);
            detachedSource.setMinimumIdle(0);
            detachedSource.setConnectionTimeout(5000);
            detachedSource.setInitializationFailTimeout(0);
            detachedSource.setAutoCommit(false);
            detachedSource.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
            try (Connection detachedConnection = detachedSource.getConnection()) {
                assertThat(detachedConnection.getAutoCommit()).isFalse();
                assertThat(detachedConnection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            }
            JdbcRankingSnapshotStore adapter = new JdbcRankingSnapshotStore(new JdbcTemplate(detachedSource));
            TransactionTemplate outer = new TransactionTemplate(transactionManager);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

            outer.executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(detachedSource)).isFalse();

                assertThatThrownBy(() -> adapter.find(key)).isInstanceOf(IllegalStateException.class);

                // JdbcTemplate must not acquire and bind the other pool's autoCommit=false connection as a side effect.
                assertThat(TransactionSynchronizationManager.hasResource(detachedSource)).isFalse();
            });
        }
        assertSnapshotCounts(0, 0);
        assertThat(sourceRows()).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "input", "checksum", "identity"})
    void storedMismatchFailsWithoutRecopyingSourceOrOverwritingEvidence(String corruption) {
        seed(jdbc, fixture());
        service.freeze(key);
        switch (corruption) {
            case "metadata" -> jdbc.update("UPDATE weekly_ranking_snapshot SET input_count=input_count+1");
            case "input" -> jdbc.update("UPDATE weekly_ranking_input SET quantity=quantity+1 WHERE order_id=42 AND product_id=7");
            case "checksum" -> jdbc.update("UPDATE weekly_ranking_snapshot SET input_checksum=?", "0".repeat(64));
            case "identity" -> jdbc.update("UPDATE weekly_ranking_snapshot SET revision=revision+1");
            default -> throw new IllegalArgumentException("Unknown fixture corruption");
        }
        var unchangedManifest = manifestRows();
        var unchangedInputs = inputRows();
        var unchangedSource = sourceRows();
        clearInvocations(store);

        assertThatThrownBy(() -> service.freeze(key)).isInstanceOf(RankingSnapshotConflictException.class);

        verify(store, never()).readSourcePage(any(), nullable(RankingPurchase.class), anyInt());
        verify(store, never()).insertManifest(any());
        verify(store, never()).insertInputs(any(), anyList());
        assertThat(manifestRows()).isEqualTo(unchangedManifest);
        assertThat(inputRows()).isEqualTo(unchangedInputs);
        assertThat(sourceRows()).isEqualTo(unchangedSource);
    }

    @Test
    void databaseRejectsAnAlternateSnapshotIdForAnExistingPeriodAndRevision() {
        seed(jdbc, fixture());
        service.freeze(key);
        var unchangedManifest = manifestRows();
        var unchangedInputs = inputRows();
        var unchangedSource = sourceRows();

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO weekly_ranking_snapshot
                (snapshot_id, period_start, period_end, revision, frozen_at_second, frozen_at_nano,
                 input_count, product_count, checksum_version, input_checksum, input_state)
            SELECT 'different-snapshot-id', period_start, period_end, revision, frozen_at_second, frozen_at_nano,
                   input_count, product_count, checksum_version, input_checksum, input_state
            FROM weekly_ranking_snapshot WHERE snapshot_id=?
            """, key.snapshotId())).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_weekly_ranking_period_revision");

        assertThat(manifestRows()).isEqualTo(unchangedManifest);
        assertThat(inputRows()).isEqualTo(unchangedInputs);
        assertThat(sourceRows()).isEqualTo(unchangedSource);
    }

    private static List<RankingPurchase> fixture() {
        return List.of(
            new RankingPurchase(42, 7, 2, Instant.parse("2026-08-17T01:00:00Z")),
            new RankingPurchase(42, 8, 1, Instant.parse("2026-08-17T01:00:00Z")),
            new RankingPurchase(43, 7, 3, Instant.parse("2026-08-18T01:00:00Z")),
            new RankingPurchase(44, 9, 5, Instant.parse("2026-08-19T01:00:00Z"))
        );
    }

    private static void seed(JdbcTemplate target, List<RankingPurchase> purchases) {
        Set<Long> orders = new HashSet<>();
        for (RankingPurchase purchase : purchases) {
            if (orders.add(purchase.orderId())) {
                seedConfirmation(target, purchase);
            }
            seedActivity(target, purchase);
        }
    }

    private static void seedConfirmation(JdbcTemplate target, RankingPurchase purchase) {
        target.update("""
            INSERT INTO processed_order_confirmation
                (event_id, order_id, event_type, schema_version, confirmed_at_second, confirmed_at_nano)
            VALUES (?, ?, 'OrderConfirmed', 1, ?, ?)
            """, "test-order-" + purchase.orderId(), purchase.orderId(),
            purchase.confirmedAt().getEpochSecond(), purchase.confirmedAt().getNano());
    }

    private static void seedActivity(JdbcTemplate target, RankingPurchase purchase) {
        target.update("""
            INSERT INTO product_purchase_activity
                (order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano) VALUES (?, ?, ?, ?, ?)
            """, purchase.orderId(), purchase.productId(), purchase.quantity(),
            purchase.confirmedAt().getEpochSecond(), purchase.confirmedAt().getNano());
    }

    private List<RankingPurchase> readInputs(WeeklyRankingKey snapshotKey) {
        return jdbc.query("""
            SELECT order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano
            FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY order_id, product_id
            """, (row, number) -> new RankingPurchase(row.getLong("order_id"), row.getLong("product_id"), row.getInt("quantity"),
            Instant.ofEpochSecond(row.getLong("confirmed_at_second"), row.getInt("confirmed_at_nano"))), snapshotKey.snapshotId());
    }

    private List<Map<String, Object>> sourceRows() {
        return jdbc.queryForList("SELECT * FROM product_purchase_activity ORDER BY id");
    }

    private List<Map<String, Object>> manifestRows() {
        return jdbc.queryForList("SELECT * FROM weekly_ranking_snapshot ORDER BY snapshot_id");
    }

    private List<Map<String, Object>> inputRows() {
        return jdbc.queryForList("SELECT * FROM weekly_ranking_input ORDER BY snapshot_id, order_id, product_id");
    }

    private void assertSnapshotCounts(long manifests, long inputs) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_snapshot", Long.class)).isEqualTo(manifests);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_input", Long.class)).isEqualTo(inputs);
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(message);
        }
    }

    private static final class TestWriteFailure extends RuntimeException {
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(MYSQL.getJdbcUrl());
            source.setUsername(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            source.setMaximumPoolSize(6);
            source.setMinimumIdle(0);
            source.setConnectionTimeout(5000);
            source.setInitializationFailTimeout(0);
            new ResourceDatabasePopulator(
                new ClassPathResource("fixtures/purchase/001-purchase-activity.sql"),
                new ClassPathResource("db/schema/001-weekly-ranking-input.sql")
            ).execute(source);
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(HikariDataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        JdbcTransactionManager transactionManager(HikariDataSource source) {
            return new JdbcTransactionManager(source);
        }

        @Bean
        RankingSnapshotStore store(JdbcTemplate template) {
            return spy(new JdbcRankingSnapshotStore(template));
        }

        @Bean
        WeeklyRankingSnapshotService service(RankingSnapshotStore snapshotStore) {
            return new WeeklyRankingSnapshotService(snapshotStore, Clock.fixed(FROZEN_AT, ZoneOffset.UTC), 2);
        }
    }
}
