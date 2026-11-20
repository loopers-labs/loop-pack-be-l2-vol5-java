package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.RankingScoreStore;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Repository
public class JdbcRankingScoreStore implements RankingScoreStore {
    private final JdbcTemplate jdbc;

    public JdbcRankingScoreStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public ProductScore aggregate(String snapshotId, long productId) {
        requireDatabaseTransaction();
        return readFrozenTotal(snapshotId, productId);
    }

    private ProductScore readFrozenTotal(String snapshotId, long productId) {
        BigDecimal sum = jdbc.queryForObject("""
            SELECT SUM(i.quantity)
            FROM weekly_ranking_input i
            JOIN weekly_ranking_snapshot s ON s.snapshot_id=i.snapshot_id
            WHERE i.snapshot_id=? AND i.product_id=? AND s.input_state='FROZEN'
            """, BigDecimal.class, snapshotId, productId);
        if (sum == null || sum.signum() <= 0) {
            throw new RankingSnapshotConflictException("Frozen snapshot has no positive total for the requested product");
        }
        try {
            return new ProductScore(snapshotId, productId, sum.longValueExact());
        } catch (ArithmeticException exception) {
            throw new RankingSnapshotConflictException("Frozen product total is outside the exact long range", exception);
        }
    }

    @Override
    public void writeScores(String snapshotId, List<ProductScore> scores) {
        requireDatabaseTransaction();
        if (snapshotId == null || scores == null
            || scores.stream().anyMatch(score -> score == null || !snapshotId.equals(score.snapshotId()))) {
            throw new IllegalArgumentException("Every score must belong to the writer's snapshot");
        }
        var states = jdbc.query("""
            SELECT input_state, calculation_state FROM weekly_ranking_snapshot WHERE snapshot_id=? FOR UPDATE
            """, (row, number) -> new SnapshotState(row.getString("input_state"), row.getString("calculation_state")), snapshotId);
        if (states.size() != 1 || !"FROZEN".equals(states.getFirst().inputState())
            || !"CALCULATING".equals(states.getFirst().calculationState())) {
            throw new RankingSnapshotConflictException("Only a frozen snapshot still being calculated accepts stage writes");
        }
        for (ProductScore score : scores) {
            if (readFrozenTotal(snapshotId, score.productId()).score() != score.score()) {
                throw new RankingSnapshotConflictException("Stage score differs from its frozen absolute total");
            }
            jdbc.update("""
                INSERT INTO weekly_ranking_score (snapshot_id, product_id, score) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE score=?
                """, snapshotId, score.productId(), score.score(), score.score());
        }
    }

    private void requireDatabaseTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Score access requires an active transaction on this DataSource");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), jdbc.getDataSource())
                || connection.getAutoCommit()) {
                throw new IllegalStateException("Score access must use this DataSource's transaction-bound connection");
            }
            return null;
        });
    }

    private record SnapshotState(String inputState, String calculationState) {}
}
