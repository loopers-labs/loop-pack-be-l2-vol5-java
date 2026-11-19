package com.loopers.batch.job.ranking.infrastructure;

import com.loopers.batch.job.ranking.domain.FrozenInputSummarizer;
import com.loopers.batch.job.ranking.domain.InputSummary;
import com.loopers.batch.job.ranking.domain.ProductPurchaseTotal;
import com.loopers.batch.job.ranking.domain.RankingPeriod;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.domain.SnapshotConflictException;
import com.loopers.batch.job.ranking.domain.SnapshotManifest;
import com.loopers.batch.job.ranking.domain.SnapshotStatus;

import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RankingSnapshotStore {
    private static final String MANIFEST_COLUMNS = """
            SELECT snapshot_id, period_start, revision, status, frozen_at,
                   input_count, product_count, input_checksum
            FROM ranking_snapshot WHERE snapshot_id = ?
            """;
    private static final RowMapper<SnapshotManifest> MANIFEST = (rs, n) -> new SnapshotManifest(
            rs.getString("snapshot_id"),
            new RankingPeriod(rs.getObject("period_start", LocalDate.class)),
            rs.getInt("revision"),
            SnapshotStatus.valueOf(rs.getString("status")),
            rs.getObject("frozen_at", LocalDateTime.class),
            new InputSummary(rs.getInt("input_count"), rs.getInt("product_count"), rs.getString("input_checksum")));

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate streamingJdbc;

    public RankingSnapshotStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc.setFetchSize(Integer.MIN_VALUE);
    }

    public JdbcCursorItemReader<Long> productIdReader(String readerName, SnapshotId snapshotId) {
        return new JdbcCursorItemReaderBuilder<Long>()
                .name(readerName)
                .saveState(true)
                .dataSource(dataSource)
                .sql("SELECT DISTINCT product_id FROM ranking_snapshot_input WHERE snapshot_id = ? "
                        + "ORDER BY product_id")
                .queryArguments(snapshotId.value())
                .fetchSize(Integer.MIN_VALUE)
                // MySQL 스트리밍 결과셋은 getRow()를 지원하지 않는다.
                .verifyCursorPosition(false)
                .rowMapper((rs, rowNum) -> rs.getLong(1))
                .build();
    }

    public Optional<SnapshotManifest> findManifest(SnapshotId snapshotId) {
        return first(jdbc.query(MANIFEST_COLUMNS, MANIFEST, snapshotId.value()));
    }

    public void verifyFrozenInput(SnapshotId snapshotId, SnapshotManifest manifest) {
        if (!manifest.definedAs(snapshotId)) {
            throw new SnapshotConflictException("snapshot " + snapshotId + " exists with period "
                    + manifest.period().start() + " revision " + manifest.revision());
        }
        InputSummary stored = summarizeInput(snapshotId);
        if (!stored.equals(manifest.input())) {
            throw new SnapshotConflictException("snapshot " + snapshotId + " input " + stored
                    + " differs from manifest " + manifest.input());
        }
    }

    public Optional<SnapshotManifest> lockManifest(SnapshotId snapshotId) {
        return first(jdbc.query(MANIFEST_COLUMNS + " FOR UPDATE", MANIFEST, snapshotId.value()));
    }

    public int copyActivities(SnapshotId id) {
        return jdbc.update("""
                INSERT INTO ranking_snapshot_input (snapshot_id, order_id, product_id, quantity, confirmed_at)
                SELECT ?, order_id, product_id, quantity, confirmed_at
                FROM product_purchase_activity
                WHERE confirmed_at >= ? AND confirmed_at < ?
                """, id.value(), id.period().startInclusiveUtc(), id.period().endExclusiveUtc());
    }

    public InputSummary summarizeInput(SnapshotId snapshotId) {
        var summarizer = new FrozenInputSummarizer();
        streamingJdbc.query("""
                SELECT order_id, product_id, quantity, confirmed_at
                FROM ranking_snapshot_input WHERE snapshot_id = ?
                ORDER BY order_id, product_id
                """, rs -> {
            summarizer.accept(rs.getLong("order_id"), rs.getLong("product_id"), rs.getInt("quantity"),
                    rs.getObject("confirmed_at", LocalDateTime.class));
        }, snapshotId.value());
        return summarizer.summary();
    }

    public void insertManifest(SnapshotManifest manifest) {
        jdbc.update("""
                INSERT INTO ranking_snapshot (snapshot_id, period_start, period_end, revision, status, frozen_at,
                                              input_count, product_count, input_checksum)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, manifest.snapshotId(), manifest.period().start(), manifest.period().end(),
                manifest.revision(), manifest.status().name(), manifest.frozenAt(),
                manifest.input().inputCount(), manifest.input().productCount(), manifest.input().checksum());
    }

    public ProductPurchaseTotal productTotal(SnapshotId snapshotId, long productId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS row_count, COALESCE(SUM(quantity), 0) AS quantity_sum,
                       COALESCE(MIN(quantity), 0) AS min_quantity
                FROM ranking_snapshot_input WHERE snapshot_id = ? AND product_id = ?
                """, (rs, n) -> new ProductPurchaseTotal(productId, rs.getLong("row_count"),
                rs.getLong("quantity_sum"), rs.getLong("min_quantity")), snapshotId.value(), productId);
    }

    public void markCompleted(SnapshotId snapshotId, SnapshotStatus status, String resultChecksum,
            LocalDateTime completedAtUtc) {
        int updated = jdbc.update("""
                UPDATE ranking_snapshot SET status = ?, result_checksum = ?, completed_at = ?
                WHERE snapshot_id = ? AND status = ?
                """, status.name(), resultChecksum, completedAtUtc, snapshotId.value(), SnapshotStatus.FROZEN.name());
        if (updated != 1) {
            throw new IllegalStateException("snapshot " + snapshotId + " was not FROZEN");
        }
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }
}
