package com.loopers.application.ranking;

import com.loopers.domain.ranking.CompletedRanking;
import com.loopers.domain.ranking.FrozenRankingInput;
import com.loopers.domain.ranking.RankingPurchase;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import com.loopers.domain.ranking.WeeklyRankingKey;
import com.loopers.infrastructure.ranking.JdbcRankingPublicationStore;
import com.loopers.infrastructure.ranking.JdbcRankingScoreStore;
import com.loopers.infrastructure.ranking.JdbcRankingSnapshotStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Every database operation uses this test's owned MySQL. No application/user DataSource is started. */
@Testcontainers
@Timeout(45)
class WeeklyRankingPublicationIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_publication_test");
    private static final WeeklyRankingKey FIRST = new WeeklyRankingKey(LocalDate.of(2026, 8, 17), 1);
    private static final WeeklyRankingKey NEXT = new WeeklyRankingKey(FIRST.periodStart(), 2);
    private static final Instant TIME = Instant.parse("2026-08-18T01:00:00.123456789Z");
    private static HikariDataSource source;
    private static JdbcTemplate jdbc;
    private static JdbcTransactionManager manager;
    private static TransactionTemplate transaction;
    private static JdbcRankingSnapshotStore snapshots;
    private static JdbcRankingScoreStore scores;
    private JdbcRankingPublicationStore publications;
    private WeeklyRankingPublicationService service;

    @BeforeAll
    static void prepareOwnedSchema() {
        source = new HikariDataSource();
        source.setJdbcUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        source.setMaximumPoolSize(5);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(5000);
        source.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=10");
        new ResourceDatabasePopulator(
            new ClassPathResource("db/schema/001-weekly-ranking-input.sql"),
            new ClassPathResource("db/schema/002-weekly-ranking-score.sql"),
            new ClassPathResource("db/schema/003-weekly-ranking-publication.sql")
        ).execute(source);
        jdbc = new JdbcTemplate(source);
        manager = new JdbcTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots = new JdbcRankingSnapshotStore(jdbc);
        scores = new JdbcRankingScoreStore(jdbc);
    }

    @BeforeEach
    void useRealTransactionalServiceProxy() {
        publications = spy(new JdbcRankingPublicationStore(jdbc));
        var proxy = new ProxyFactory(new WeeklyRankingPublicationService(snapshots, publications));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (WeeklyRankingPublicationService) proxy.getProxy();
    }

    @AfterEach
    void cleanOwnedRowsOnly() {
        jdbc.update("DELETE FROM weekly_ranking_publication");
        jdbc.update("DELETE FROM weekly_ranking_result");
        jdbc.update("DELETE FROM weekly_ranking_score");
        jdbc.update("DELETE FROM weekly_ranking_input");
        jdbc.update("DELETE FROM weekly_ranking_snapshot");
    }

    @AfterAll
    static void closeOwnedPool() {
        if (source != null) {
            source.close();
        }
    }

    @Test
    void publishesCompleteHandCalculatedRankingAndNanosecondInputUnchanged() {
        seed(FIRST, fixture());
        var inputBefore = jdbc.queryForList("SELECT * FROM weekly_ranking_input");

        service.publish(FIRST);

        assertThat(results(FIRST)).extracting(row -> row.get("product_id"), row -> row.get("score"), row -> row.get("ranking_position"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(7L, 5L, 1L),
                org.assertj.core.groups.Tuple.tuple(9L, 5L, 2L), org.assertj.core.groups.Tuple.tuple(8L, 1L, 3L));
        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
        assertThat(manifest(FIRST)).containsEntry("calculation_state", "COMPLETE").containsEntry("result_count", 3L)
            .containsEntry("result_checksum_version", "v1")
            .containsEntry("result_checksum", "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
        assertThat(jdbc.queryForList("SELECT * FROM weekly_ranking_input")).isEqualTo(inputBefore);
    }

    @Test
    void zeroPurchaseWeekIsPublishedWithEmptyCompletedResult() {
        seed(FIRST, List.of());
        service.publish(FIRST);

        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
        assertThat(results(FIRST)).isEmpty();
        assertThat(manifest(FIRST)).containsEntry("calculation_state", "COMPLETE").containsEntry("result_count", 0L)
            .containsEntry("result_checksum", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void repeatingTheSamePublicationLeavesRowsAndManifestAndPointerUnchanged() {
        seed(FIRST, fixture());
        service.publish(FIRST);
        var rows = results(FIRST);
        var complete = manifest(FIRST);
        var published = jdbc.queryForList("SELECT * FROM weekly_ranking_publication");

        service.publish(FIRST);

        assertThat(results(FIRST)).isEqualTo(rows);
        assertThat(manifest(FIRST)).isEqualTo(complete);
        assertThat(jdbc.queryForList("SELECT * FROM weekly_ranking_publication")).isEqualTo(published);
    }

    @Test
    void anOlderRevisionCannotReplaceANewerPublicationAndOtherWeekIsPreserved() {
        var otherWeek = new WeeklyRankingKey(FIRST.periodStart().plusWeeks(1), 1);
        seed(FIRST, fixture());
        seed(NEXT, List.of(purchase(42, 7, 9)));
        seed(otherWeek, List.of(new RankingPurchase(50, 11, 2, TIME.plusSeconds(7 * 86400))));
        service.publish(NEXT);
        service.publish(otherWeek);
        var nextRows = results(NEXT);

        service.publish(FIRST);
        service.publish(FIRST);

        assertThat(pointer()).isEqualTo(NEXT.snapshotId());
        assertThat(results(NEXT)).isEqualTo(nextRows);
        assertThat(manifest(FIRST)).containsEntry("calculation_state", "COMPLETE");
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_publication WHERE period_start=?",
            String.class, otherWeek.periodStart())).isEqualTo(otherWeek.snapshotId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "wrong", "replacement"})
    void incompleteOrIncorrectStageCannotReplaceThePreviousPublicResult(String corruption) {
        seed(FIRST, fixture());
        service.publish(FIRST);
        seed(NEXT, fixture());
        switch (corruption) {
            case "missing" -> jdbc.update("DELETE FROM weekly_ranking_score WHERE snapshot_id=? AND product_id=8", NEXT.snapshotId());
            case "extra" -> jdbc.update("INSERT INTO weekly_ranking_score VALUES (?,99,1)", NEXT.snapshotId());
            case "wrong" -> jdbc.update("UPDATE weekly_ranking_score SET score=6 WHERE snapshot_id=? AND product_id=7", NEXT.snapshotId());
            case "replacement" -> jdbc.update("UPDATE weekly_ranking_score SET product_id=99 WHERE snapshot_id=? AND product_id=8", NEXT.snapshotId());
            default -> throw new IllegalArgumentException(corruption);
        }

        assertThatThrownBy(() -> service.publish(NEXT)).isInstanceOf(RankingSnapshotConflictException.class);

        assertNotPublished(NEXT);
        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
    }

    @Test
    void corruptFrozenInputIsRejectedWithoutRepairOrSourceRecopy() {
        seed(FIRST, fixture());
        jdbc.update("UPDATE weekly_ranking_input SET quantity=9 WHERE snapshot_id=? AND order_id=42 AND product_id=7", FIRST.snapshotId());

        assertThatThrownBy(() -> service.publish(FIRST)).isInstanceOf(RankingSnapshotConflictException.class);

        assertNotPublished(FIRST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_publication", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"row", "checksum"})
    void completedResultCorruptionFailsInsteadOfOverwritingFacts(String corruption) {
        seed(FIRST, fixture());
        service.publish(FIRST);
        if ("row".equals(corruption)) {
            jdbc.update("UPDATE weekly_ranking_result SET score=99 WHERE snapshot_id=? AND product_id=7", FIRST.snapshotId());
        } else {
            jdbc.update("UPDATE weekly_ranking_snapshot SET result_checksum=? WHERE snapshot_id=?", "0".repeat(64), FIRST.snapshotId());
        }
        var beforeRows = results(FIRST);
        var beforeManifest = manifest(FIRST);

        assertThatThrownBy(() -> service.publish(FIRST)).isInstanceOf(RankingSnapshotConflictException.class);

        assertThat(results(FIRST)).isEqualTo(beforeRows);
        assertThat(manifest(FIRST)).isEqualTo(beforeManifest);
        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
    }

    @Test
    void failureAfterActualResultInsertRollsBackAndPreservesThePreviousPointer() {
        seed(FIRST, fixture());
        service.publish(FIRST);
        seed(NEXT, fixture());
        var previous = results(FIRST);
        doAnswer(call -> {
            call.callRealMethod();
            assertThat(results(NEXT)).hasSize(3);
            throw new IllegalStateException("after actual result INSERT");
        }).when(publications).insertResults(any(CompletedRanking.class));

        assertThatThrownBy(() -> service.publish(NEXT)).isInstanceOf(IllegalStateException.class)
            .hasMessage("after actual result INSERT");

        assertNotPublished(NEXT);
        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
        assertThat(results(FIRST)).isEqualTo(previous);
    }

    @Test
    void failureAfterActualPointerUpdateRollsBackResultsCompletionAndPointerTogether() {
        seed(FIRST, fixture());
        service.publish(FIRST);
        seed(NEXT, fixture());
        doAnswer(call -> {
            call.callRealMethod();
            assertThat(pointer()).isEqualTo(NEXT.snapshotId());
            assertThat(manifest(NEXT)).containsEntry("calculation_state", "COMPLETE");
            assertThat(results(NEXT)).hasSize(3);
            throw new IllegalStateException("after actual pointer UPDATE");
        }).when(publications).advancePointer(NEXT);

        assertThatThrownBy(() -> service.publish(NEXT)).isInstanceOf(IllegalStateException.class)
            .hasMessage("after actual pointer UPDATE");

        assertNotPublished(NEXT);
        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
    }

    @Test
    void resultRowsWithoutCompletionAreNotSilentlyReusedOrDeleted() {
        seed(FIRST, fixture());
        jdbc.update("INSERT INTO weekly_ranking_result VALUES (?,7,5,1)", FIRST.snapshotId());
        var before = results(FIRST);

        assertThatThrownBy(() -> service.publish(FIRST)).isInstanceOf(RankingSnapshotConflictException.class);

        assertThat(results(FIRST)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_publication", Long.class)).isZero();
    }

    @Test
    void concurrentFirstPublicationsForDifferentRevisionsConvergeToTheLatest() throws Exception {
        seed(FIRST, fixture());
        seed(NEXT, List.of(purchase(42, 7, 9)));
        var locked = new CountDownLatch(2);
        doAnswer(call -> {
            var state = call.callRealMethod();
            locked.countDown();
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            return state;
        }).when(publications).lockSnapshot(any(WeeklyRankingKey.class));

        publishConcurrently(FIRST, NEXT);

        assertThat(pointer()).isEqualTo(NEXT.snapshotId());
        assertThat(results(FIRST)).hasSize(3);
        assertThat(results(NEXT)).hasSize(1);
        assertThat(manifest(FIRST)).containsEntry("calculation_state", "COMPLETE");
        assertThat(manifest(NEXT)).containsEntry("calculation_state", "COMPLETE");
    }

    @Test
    void concurrentOldReplayAndNewPublicationKeepTheNewPointerWithoutCrossManifestLocks() throws Exception {
        seed(FIRST, fixture());
        service.publish(FIRST);
        seed(NEXT, List.of(purchase(42, 7, 9)));
        var firstRows = results(FIRST);
        var locked = new CountDownLatch(2);
        doAnswer(call -> {
            var state = call.callRealMethod();
            locked.countDown();
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            return state;
        }).when(publications).lockSnapshot(any(WeeklyRankingKey.class));

        publishConcurrently(FIRST, NEXT);

        assertThat(pointer()).isEqualTo(NEXT.snapshotId());
        assertThat(results(FIRST)).isEqualTo(firstRows);
        assertThat(results(NEXT)).hasSize(1);
    }

    @Test
    void completionReplayUsesCurrentRowsEvenWhenAnOuterRepeatableReadViewPredatesPublication() throws Exception {
        seed(FIRST, fixture());
        var viewEstablished = new CountDownLatch(1);
        var published = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var replay = executor.submit(() -> transaction.executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_result", Long.class)).isZero();
                viewEstablished.countDown();
                try {
                    assertThat(published.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted before publication replay", exception);
                }
                service.publish(FIRST);
            }));
            assertThat(viewEstablished.await(10, TimeUnit.SECONDS)).isTrue();
            service.publish(FIRST);
            published.countDown();
            replay.get(20, TimeUnit.SECONDS);
        } finally {
            published.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
        assertThat(results(FIRST)).hasSize(3);
    }

    @Test
    void manifestLockPreventsStageWritingAfterPublication() {
        seed(FIRST, fixture());
        service.publish(FIRST);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> scores.writeScores(FIRST.snapshotId(),
            List.of(scores.aggregate(FIRST.snapshotId(), 7))))).isInstanceOf(RankingSnapshotConflictException.class);
        assertThat(pointer()).isEqualTo(FIRST.snapshotId());
    }

    @Test
    void databaseRejectsMismatchedPointerIdentityAndInvalidRank() {
        seed(FIRST, fixture());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO weekly_ranking_publication VALUES (?,?,?)",
            FIRST.periodStart(), FIRST.snapshotId(), 2)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO weekly_ranking_result VALUES (?,7,5,0)", FIRST.snapshotId()))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE weekly_ranking_snapshot SET calculation_state='COMPLETE' WHERE snapshot_id=?",
            FIRST.snapshotId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void publicationRequiresAnExistingFrozenSnapshotAndTheCorrectDatabaseTransaction() {
        assertThatThrownBy(() -> service.publish(FIRST)).isInstanceOf(RankingSnapshotConflictException.class);
        assertThatThrownBy(() -> publications.lockSnapshot(FIRST)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> publications.advancePointer(FIRST)).isInstanceOf(IllegalStateException.class);
    }

    private void publishConcurrently(WeeklyRankingKey firstKey, WeeklyRankingKey secondKey) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.publish(firstKey));
            var second = executor.submit(() -> service.publish(secondKey));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void seed(WeeklyRankingKey key, List<RankingPurchase> purchases) {
        transaction.executeWithoutResult(status -> {
            var input = new FrozenRankingInput(key, TIME, purchases);
            snapshots.insertManifest(input);
            snapshots.insertInputs(key.snapshotId(), input.purchases());
            var totals = purchases.stream().map(RankingPurchase::productId).distinct()
                .map(product -> scores.aggregate(key.snapshotId(), product)).toList();
            scores.writeScores(key.snapshotId(), totals);
        });
    }

    private static void assertNotPublished(WeeklyRankingKey key) {
        assertThat(results(key)).isEmpty();
        assertThat(manifest(key)).containsEntry("calculation_state", "CALCULATING").containsEntry("result_count", null)
            .containsEntry("result_checksum_version", null).containsEntry("result_checksum", null);
    }

    private static Map<String, Object> manifest(WeeklyRankingKey key) {
        return jdbc.queryForMap("SELECT * FROM weekly_ranking_snapshot WHERE snapshot_id=?", key.snapshotId());
    }

    private static List<Map<String, Object>> results(WeeklyRankingKey key) {
        return jdbc.queryForList("SELECT * FROM weekly_ranking_result WHERE snapshot_id=? ORDER BY ranking_position", key.snapshotId());
    }

    private static String pointer() {
        return jdbc.queryForObject("SELECT snapshot_id FROM weekly_ranking_publication WHERE period_start=?", String.class, FIRST.periodStart());
    }

    private static RankingPurchase purchase(long order, long product, int quantity) {
        return new RankingPurchase(order, product, quantity, TIME);
    }

    private static List<RankingPurchase> fixture() {
        return List.of(purchase(42, 7, 2), purchase(42, 8, 1), purchase(43, 7, 3), purchase(44, 9, 5));
    }
}
