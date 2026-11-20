package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.CompletedRanking;
import com.loopers.domain.ranking.ProductScore;
import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.RankingPublicationStore;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import com.loopers.domain.ranking.WeeklyRankingKey;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

@Repository
public class JdbcRankingPublicationStore implements RankingPublicationStore {
    private final JdbcTemplate jdbc;

    public JdbcRankingPublicationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public PublicationState lockSnapshot(WeeklyRankingKey key) {
        requireRepeatableReadTransaction();
        var states = jdbc.query("""
            SELECT period_start, period_end, revision, input_state, calculation_state,
                   result_count, result_checksum_version, result_checksum
            FROM weekly_ranking_snapshot WHERE snapshot_id=? FOR UPDATE
            """, (row, index) -> {
                if (!key.periodStart().equals(row.getDate("period_start").toLocalDate())
                    || !key.periodEnd().equals(row.getDate("period_end").toLocalDate())
                    || key.revision() != row.getInt("revision") || !"FROZEN".equals(row.getString("input_state"))) {
                    throw new RankingSnapshotConflictException("Publication snapshot identity or input state conflicts");
                }
                return new PublicationState(row.getString("calculation_state"), row.getObject("result_count", Long.class),
                    row.getString("result_checksum_version"), row.getString("result_checksum"));
            }, key.snapshotId());
        if (states.size() != 1) {
            throw new RankingSnapshotConflictException("Publication requires exactly one frozen snapshot");
        }
        return states.getFirst();
    }

    @Override
    public List<ProductScore> readScores(String snapshotId) {
        requireRepeatableReadTransaction();
        // Current reads follow the manifest mutex, even if the outer tasklet already established an RR read view.
        return jdbc.query("""
            SELECT product_id, score FROM weekly_ranking_score WHERE snapshot_id=? ORDER BY product_id FOR SHARE
            """, (row, index) -> new ProductScore(snapshotId, row.getLong("product_id"), row.getLong("score")), snapshotId);
    }

    @Override
    public List<RankedProduct> readResults(String snapshotId, boolean complete) {
        requireRepeatableReadTransaction();
        // Calculating snapshots have no legitimate concurrent result writer under the manifest mutex.
        // Avoid gap-locking an empty result range before INSERT, which can deadlock distinct initial publications.
        String lock = complete ? " FOR SHARE" : "";
        return jdbc.query("""
            SELECT product_id, score, ranking_position FROM weekly_ranking_result
            WHERE snapshot_id=? ORDER BY ranking_position
            """ + lock, (row, index) -> new RankedProduct(row.getLong("product_id"), row.getLong("score"), row.getLong("ranking_position")), snapshotId);
    }

    @Override
    public void insertResults(CompletedRanking ranking) {
        requireRepeatableReadTransaction();
        if (ranking.products().isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
            INSERT INTO weekly_ranking_result (snapshot_id, product_id, score, ranking_position) VALUES (?, ?, ?, ?)
            """, ranking.products(), 500, (statement, product) -> {
                statement.setString(1, ranking.key().snapshotId());
                statement.setLong(2, product.productId());
                statement.setLong(3, product.score());
                statement.setLong(4, product.rank());
            });
    }

    @Override
    public void complete(CompletedRanking ranking) {
        requireRepeatableReadTransaction();
        int changed = jdbc.update("""
            UPDATE weekly_ranking_snapshot
            SET calculation_state='COMPLETE', result_count=?, result_checksum_version=?, result_checksum=?
            WHERE snapshot_id=? AND calculation_state='CALCULATING' AND input_state='FROZEN'
            """, ranking.products().size(), ranking.checksumVersion(), ranking.checksum(), ranking.key().snapshotId());
        if (changed != 1) {
            throw new RankingSnapshotConflictException("Only the locked calculating snapshot can become complete");
        }
    }

    @Override
    public void advancePointer(WeeklyRankingKey key) {
        requireRepeatableReadTransaction();
        if (!"COMPLETE".equals(lockSnapshot(key).calculationState())) {
            throw new RankingSnapshotConflictException("Only a complete snapshot can be published");
        }
        // A unique period row serializes the initial insert and later advances. Never lock another revision's manifest.
        // Assignment order matters: compare against the previous revision before replacing that revision value.
        jdbc.update("""
            INSERT INTO weekly_ranking_publication (period_start, snapshot_id, revision) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
                snapshot_id=IF(VALUES(revision)>revision, VALUES(snapshot_id), snapshot_id),
                revision=GREATEST(revision, VALUES(revision))
            """, key.periodStart(), key.snapshotId(), key.revision());
        var pointers = jdbc.query("""
            SELECT snapshot_id, revision FROM weekly_ranking_publication WHERE period_start=? FOR UPDATE
            """, (row, index) -> new Pointer(row.getString("snapshot_id"), row.getInt("revision")), key.periodStart());
        if (pointers.size() != 1 || pointers.getFirst().revision() < key.revision()
            || (pointers.getFirst().revision() == key.revision() && !pointers.getFirst().snapshotId().equals(key.snapshotId()))) {
            throw new RankingSnapshotConflictException("Stored publication pointer conflicts with the requested revision");
        }
    }

    private void requireRepeatableReadTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Publication requires an active transaction on this DataSource");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), jdbc.getDataSource())
                || connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException("Publication requires this DataSource's RR transaction-bound connection");
            }
            return null;
        });
    }

    private record Pointer(String snapshotId, int revision) {}
}
