package com.loopers.application.ranking;

import com.loopers.domain.ranking.FrozenRankingInput;
import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.RankingPurchase;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import com.loopers.domain.ranking.WeeklyRankingKey;
import com.loopers.infrastructure.ranking.JdbcRankingScoreStore;
import com.loopers.infrastructure.ranking.JdbcRankingSnapshotStore;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real stage writes use only this class's owned MySQL; no user database or global fixture. */
@Testcontainers
@Timeout(30)
class WeeklyRankingScoreStoreIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w9_score_stage_test");
    private static final WeeklyRankingKey KEY = new WeeklyRankingKey(LocalDate.of(2026, 8, 17), 1);
    private static final Instant CONFIRMED = Instant.parse("2026-08-18T01:00:00.123456789Z");
    private static HikariDataSource source;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static JdbcRankingSnapshotStore snapshots;
    private static JdbcRankingScoreStore scores;

    @BeforeAll
    static void prepareOwnedSchema() {
        source = new HikariDataSource();
        source.setJdbcUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        source.setMaximumPoolSize(3);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(5000);
        source.setInitializationFailTimeout(0);
        new ResourceDatabasePopulator(
            new ClassPathResource("db/schema/001-weekly-ranking-input.sql"),
            new ClassPathResource("db/schema/002-weekly-ranking-score.sql")
        ).execute(source);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new JdbcTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshots = new JdbcRankingSnapshotStore(jdbc);
        scores = new JdbcRankingScoreStore(jdbc);
    }

    @AfterEach
    void cleanOwnedRowsOnly() {
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
    void sumsEveryFrozenOrderItemForOneProduct() {
        seed(KEY, List.of(purchase(42, 7, 2), purchase(42, 8, 1), purchase(43, 7, 3), purchase(44, 9, 5)));

        var result = transaction.execute(status -> List.of(
            scores.aggregate(KEY.snapshotId(), 7), scores.aggregate(KEY.snapshotId(), 8), scores.aggregate(KEY.snapshotId(), 9)
        ));

        assertThat(result).containsExactly(new ProductScore(KEY.snapshotId(), 7, 5),
            new ProductScore(KEY.snapshotId(), 8, 1), new ProductScore(KEY.snapshotId(), 9, 5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isZero();
    }

    @Test
    void writingTheSameAbsoluteScoreTwiceKeepsOneRowAndTheSameValue() {
        seed(KEY, List.of(purchase(42, 7, 2), purchase(43, 7, 3)));
        var score = new ProductScore(KEY.snapshotId(), 7, 5);

        transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(), List.of(score)));
        var first = jdbc.queryForList("SELECT * FROM weekly_ranking_score");
        transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(), List.of(score)));

        assertThat(jdbc.queryForList("SELECT * FROM weekly_ranking_score")).isEqualTo(first);
        assertThat(stored(KEY.snapshotId(), 7)).isEqualTo(5L);
        assertThat(first).hasSize(1);
    }

    @Test
    void sameProductInAnotherSnapshotHasAnIndependentAbsoluteScore() {
        var next = new WeeklyRankingKey(KEY.periodStart(), 2);
        seed(KEY, List.of(purchase(42, 7, 5)));
        seed(next, List.of(purchase(42, 7, 5), purchase(45, 7, 4)));

        transaction.executeWithoutResult(status -> {
            scores.writeScores(KEY.snapshotId(), List.of(scores.aggregate(KEY.snapshotId(), 7)));
            scores.writeScores(next.snapshotId(), List.of(scores.aggregate(next.snapshotId(), 7)));
        });

        assertThat(stored(KEY.snapshotId(), 7)).isEqualTo(5L);
        assertThat(stored(next.snapshotId(), 7)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isEqualTo(2L);
    }

    @Test
    void aValidTotalAboveIntegerMaxIsStoredWithoutOverflow() {
        seed(KEY, List.of(purchase(42, 7, Integer.MAX_VALUE), purchase(43, 7, 1)));

        transaction.executeWithoutResult(status -> {
            var total = scores.aggregate(KEY.snapshotId(), 7);
            assertThat(total.score()).isEqualTo(2147483648L);
            scores.writeScores(KEY.snapshotId(), List.of(total));
        });

        assertThat(stored(KEY.snapshotId(), 7)).isEqualTo(2147483648L);
    }

    @Test
    void aSqlSumOutsideLongRangeFailsInsteadOfWrappingOrTruncating() {
        seed(KEY, List.of(purchase(42, 7, 1), purchase(43, 7, 1)));
        // Test-only widening creates an otherwise billions-of-rows overflow fixture. Production INT DDL is unchanged.
        jdbc.execute("ALTER TABLE weekly_ranking_input MODIFY quantity BIGINT NOT NULL");
        try {
            jdbc.update("UPDATE weekly_ranking_input SET quantity=? WHERE order_id=42", Long.MAX_VALUE);
            assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM weekly_ranking_input", BigDecimal.class))
                .isEqualByComparingTo("9223372036854775808");

            assertThatThrownBy(() -> transaction.execute(status -> scores.aggregate(KEY.snapshotId(), 7)))
                .isInstanceOf(RankingSnapshotConflictException.class).hasCauseInstanceOf(ArithmeticException.class);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isZero();
        } finally {
            jdbc.update("DELETE FROM weekly_ranking_input");
            jdbc.execute("ALTER TABLE weekly_ranking_input MODIFY quantity INT NOT NULL");
        }
    }

    @Test
    void absentFrozenProductDoesNotBecomeAZeroScore() {
        seed(KEY, List.of(purchase(42, 7, 5)));

        assertThatThrownBy(() -> transaction.execute(status -> scores.aggregate(KEY.snapshotId(), 99)))
            .isInstanceOf(RankingSnapshotConflictException.class);
        assertThatThrownBy(() -> transaction.execute(status -> scores.aggregate("missing-snapshot", 7)))
            .isInstanceOf(RankingSnapshotConflictException.class);
    }

    @Test
    void crossSnapshotAndIncorrectAbsoluteTotalsAreRejected() {
        seed(KEY, List.of(purchase(42, 7, 5)));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(),
            List.of(new ProductScore("another-snapshot", 7, 5))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(),
            List.of(new ProductScore(KEY.snapshotId(), 7, 10))))).isInstanceOf(RankingSnapshotConflictException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(),
            List.of(new ProductScore(KEY.snapshotId(), 99, 1))))).isInstanceOf(RankingSnapshotConflictException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isZero();
    }

    @Test
    void aCompletedSnapshotCannotBeWrittenAgainEvenWithTheSameScore() {
        seed(KEY, List.of(purchase(42, 7, 5)));
        var score = new ProductScore(KEY.snapshotId(), 7, 5);
        transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(), List.of(score)));
        // Publication will own this state transition in phase 4; this only verifies the stage writer's guard.
        jdbc.update("UPDATE weekly_ranking_snapshot SET calculation_state='COMPLETE' WHERE snapshot_id=?", KEY.snapshotId());
        var before = jdbc.queryForList("SELECT * FROM weekly_ranking_score");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(), List.of(score))))
            .isInstanceOf(RankingSnapshotConflictException.class);

        assertThat(jdbc.queryForList("SELECT * FROM weekly_ranking_score")).isEqualTo(before);
    }

    @Test
    void failureAfterAnActualScoreInsertRollsBackTheWholeTransaction() {
        seed(KEY, List.of(purchase(42, 7, 5), purchase(42, 8, 1)));
        var failure = new IllegalStateException("after actual score SQL");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            scores.writeScores(KEY.snapshotId(), List.of(new ProductScore(KEY.snapshotId(), 7, 5)));
            assertThat(stored(KEY.snapshotId(), 7)).isEqualTo(5L);
            throw failure;
        })).isSameAs(failure);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_input", Long.class)).isEqualTo(2L);
        transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(),
            List.of(new ProductScore(KEY.snapshotId(), 7, 5), new ProductScore(KEY.snapshotId(), 8, 1))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isEqualTo(2L);
    }

    @Test
    void missingManifestAndNoTransactionDoNotAllowWrites() {
        var score = new ProductScore(KEY.snapshotId(), 7, 5);
        assertThatThrownBy(() -> scores.aggregate(KEY.snapshotId(), 7)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scores.writeScores(KEY.snapshotId(), List.of(score))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> scores.writeScores(KEY.snapshotId(), List.of(score))))
            .isInstanceOf(RankingSnapshotConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isZero();
    }

    @Test
    void stageSchemaRejectsInvalidAmountsProductsAndOrphanSnapshots() {
        seed(KEY, List.of(purchase(42, 7, 5)));
        assertDatabaseConstraint(() -> jdbc.update("INSERT INTO weekly_ranking_score VALUES (?,7,0)", KEY.snapshotId()),
            3819, "ck_weekly_score_value");
        assertDatabaseConstraint(() -> jdbc.update("INSERT INTO weekly_ranking_score VALUES (?,0,5)", KEY.snapshotId()),
            3819, "ck_weekly_score_product");
        assertDatabaseConstraint(() -> jdbc.update("INSERT INTO weekly_ranking_score VALUES ('missing',7,5)"),
            1452, "fk_weekly_score_snapshot");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM weekly_ranking_score", Long.class)).isZero();
    }

    private static void assertDatabaseConstraint(Runnable insert, int mysqlCode, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class).satisfies(failure -> {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
            assertThat(cause).isInstanceOf(SQLException.class).hasMessageContaining(constraint);
            assertThat(((SQLException) cause).getErrorCode()).isEqualTo(mysqlCode);
        });
    }

    private static RankingPurchase purchase(long orderId, long productId, int quantity) {
        return new RankingPurchase(orderId, productId, quantity, CONFIRMED);
    }

    private static void seed(WeeklyRankingKey key, List<RankingPurchase> purchases) {
        var frozen = new FrozenRankingInput(key, Instant.parse("2026-08-24T01:00:00Z"), purchases);
        transaction.executeWithoutResult(status -> {
            snapshots.insertManifest(frozen);
            snapshots.insertInputs(key.snapshotId(), purchases);
        });
    }

    private static Long stored(String snapshotId, long productId) {
        return jdbc.queryForObject("SELECT score FROM weekly_ranking_score WHERE snapshot_id=? AND product_id=?",
            Long.class, snapshotId, productId);
    }
}
