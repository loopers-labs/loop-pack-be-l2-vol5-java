package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.FrozenRankingInput;
import com.loopers.domain.ranking.RankingPurchase;
import com.loopers.domain.ranking.RankingSnapshotConflictException;
import com.loopers.domain.ranking.RankingSnapshotStore;
import com.loopers.domain.ranking.WeeklyRankingKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRankingSnapshotStore implements RankingSnapshotStore {
    private final JdbcTemplate jdbc;

    public JdbcRankingSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FrozenRankingInput> find(WeeklyRankingKey key) {
        requireRepeatableReadTransaction();
        try {
            var manifests = jdbc.query("""
                SELECT period_start, period_end, revision, frozen_at_second, frozen_at_nano,
                       input_count, product_count, checksum_version, input_checksum, input_state
                FROM weekly_ranking_snapshot WHERE snapshot_id=?
                """, (row, index) -> new Manifest(
                    new WeeklyRankingKey(row.getDate("period_start").toLocalDate(), row.getInt("revision")),
                    row.getDate("period_end").toLocalDate(),
                    exactInstant(row.getLong("frozen_at_second"), row.getInt("frozen_at_nano")),
                    row.getLong("input_count"), row.getLong("product_count"), row.getString("checksum_version"),
                    row.getString("input_checksum"), row.getString("input_state")), key.snapshotId());
            if (manifests.isEmpty()) {
                return Optional.empty();
            }
            var manifest = manifests.getFirst();
            if (!manifest.key().equals(key) || !manifest.periodEnd().equals(key.periodEnd())
                || !"FROZEN".equals(manifest.state())) {
                throw new RankingSnapshotConflictException("Stored snapshot identity or input state conflicts");
            }
            var purchases = jdbc.query("""
                SELECT order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano
                FROM weekly_ranking_input WHERE snapshot_id=? ORDER BY order_id, product_id
                """, (row, index) -> purchase(row), key.snapshotId());
            var input = new FrozenRankingInput(key, manifest.frozenAt(), purchases);
            if (manifest.inputCount() != input.inputCount() || manifest.productCount() != input.productCount()
                || !input.checksumVersion().equals(manifest.checksumVersion())
                || !input.inputChecksum().equals(manifest.checksum())) {
                throw new RankingSnapshotConflictException("Stored snapshot input does not match its manifest");
            }
            return Optional.of(input);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new RankingSnapshotConflictException("Stored snapshot contains invalid facts", exception);
        }
    }

    @Override
    public List<RankingPurchase> readSourcePage(WeeklyRankingKey key, RankingPurchase after, int pageSize) {
        requireRepeatableReadTransaction();
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Source page size must be positive");
        }
        String select = """
            SELECT order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano
            FROM product_purchase_activity
            WHERE confirmed_at_second >= ? AND confirmed_at_second < ?
            """;
        long start = key.startInclusive().getEpochSecond();
        long end = key.endExclusive().getEpochSecond();
        if (after == null) {
            return jdbc.query(select + " ORDER BY order_id, product_id LIMIT ?",
                (row, index) -> purchase(row), start, end, pageSize);
        }
        return jdbc.query(select + " AND (order_id, product_id) > (?, ?) ORDER BY order_id, product_id LIMIT ?",
            (row, index) -> purchase(row), start, end, after.orderId(), after.productId(), pageSize);
    }

    @Override
    public void insertManifest(FrozenRankingInput input) {
        requireRepeatableReadTransaction();
        jdbc.update("""
            INSERT INTO weekly_ranking_snapshot
                (snapshot_id, period_start, period_end, revision, frozen_at_second, frozen_at_nano,
                 input_count, product_count, checksum_version, input_checksum, input_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FROZEN')
            """, input.key().snapshotId(), input.key().periodStart(), input.key().periodEnd(), input.key().revision(),
            input.frozenAt().getEpochSecond(), input.frozenAt().getNano(), input.inputCount(), input.productCount(),
            input.checksumVersion(), input.inputChecksum());
    }

    @Override
    public void insertInputs(String snapshotId, List<RankingPurchase> purchases) {
        requireRepeatableReadTransaction();
        if (purchases.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
            INSERT INTO weekly_ranking_input
                (snapshot_id, order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano)
            VALUES (?, ?, ?, ?, ?, ?)
            """, purchases, purchases.size(), (statement, purchase) -> {
                statement.setString(1, snapshotId);
                statement.setLong(2, purchase.orderId());
                statement.setLong(3, purchase.productId());
                statement.setInt(4, purchase.quantity());
                statement.setLong(5, purchase.confirmedAt().getEpochSecond());
                statement.setInt(6, purchase.confirmedAt().getNano());
            });
    }

    private void requireRepeatableReadTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Snapshot access requires an active transaction on this DataSource");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            // JdbcTemplate supplies a close-suppressing proxy; compare its underlying bound connection.
            if (!DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), jdbc.getDataSource())
                || connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException("Snapshot access requires this DataSource's REPEATABLE_READ transaction");
            }
            return null;
        });
    }

    private RankingPurchase purchase(ResultSet row) throws SQLException {
        return new RankingPurchase(row.getLong("order_id"), row.getLong("product_id"), row.getInt("quantity"),
            exactInstant(row.getLong("confirmed_at_second"), row.getInt("confirmed_at_nano")));
    }

    private Instant exactInstant(long second, int nano) {
        if (nano < 0 || nano > 999_999_999) {
            // Instant.ofEpochSecond normalizes out-of-range nanos; persisted corruption must not be normalized away.
            throw new RankingSnapshotConflictException("Stored instant has out-of-range nanoseconds");
        }
        return Instant.ofEpochSecond(second, nano);
    }

    private record Manifest(WeeklyRankingKey key, LocalDate periodEnd, Instant frozenAt, long inputCount,
                            long productCount, String checksumVersion, String checksum, String state) {}
}
