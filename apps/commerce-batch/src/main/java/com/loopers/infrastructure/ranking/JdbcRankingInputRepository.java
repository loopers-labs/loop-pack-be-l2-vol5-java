package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.RankingInputRepository;
import com.loopers.domain.ranking.WeeklyRankingRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class JdbcRankingInputRepository implements RankingInputRepository {

    private final JdbcTemplate jdbc;

    public JdbcRankingInputRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<RankingInputManifest> findSnapshot(String snapshotId) {
        return jdbc.query(RankingSnapshotRows.SELECT + "WHERE snapshot_id = ?", RankingSnapshotRows.MAPPER, snapshotId)
            .stream().findFirst();
    }

    @Override
    public Optional<RankingInputManifest> findSnapshotByPeriodAndRevision(LocalDate periodStart, long revision) {
        return jdbc.query(RankingSnapshotRows.SELECT + "WHERE period_start = ? AND revision = ?",
            RankingSnapshotRows.MAPPER, periodStart, revision).stream().findFirst();
    }

    @Override
    public boolean hasOrphanData(String snapshotId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ranking_snapshot_input WHERE snapshot_id = ?) "
                + "OR EXISTS(SELECT 1 FROM ranking_score_stage WHERE snapshot_id = ?) "
                + "OR EXISTS(SELECT 1 FROM ranking_result WHERE snapshot_id = ?) "
                + "OR EXISTS(SELECT 1 FROM ranking_publication WHERE published_snapshot_id = ?)",
            Boolean.class, snapshotId, snapshotId, snapshotId, snapshotId));
    }

    @Override
    public void copyInput(WeeklyRankingRequest request) {
        jdbc.update("INSERT INTO ranking_snapshot_input "
                + "(snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at, deleted_at) "
                + "SELECT ?, order_id, product_id, quantity, confirmed_at, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL "
                + "FROM product_purchase_activity WHERE confirmed_at >= ? AND confirmed_at < ?",
            request.snapshotId(), request.periodStart().atStartOfDay(), request.periodEnd().atStartOfDay());
    }

    @Override
    public LocalDateTime readDatabaseUtcNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    @Override
    public InputCounts countInput(String snapshotId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) AS input_count, COUNT(DISTINCT product_id) AS product_count "
                + "FROM ranking_snapshot_input WHERE snapshot_id = ?",
            (rs, rowNum) -> new InputCounts(rs.getLong("input_count"), rs.getLong("product_count")), snapshotId);
    }

    @Override
    public void forEachInputOrdered(String snapshotId, InputRowHandler handler) {
        jdbc.query("SELECT order_id, product_id, quantity, confirmed_at FROM ranking_snapshot_input "
                + "WHERE snapshot_id = ? ORDER BY order_id, product_id",
            (RowCallbackHandler) rs -> handler.accept(rs.getLong("order_id"), rs.getLong("product_id"), rs.getLong("quantity"),
                rs.getObject("confirmed_at", LocalDateTime.class)), snapshotId);
    }

    @Override
    public void insertReadySnapshot(RankingInputManifest manifest) {
        int inserted = jdbc.update("INSERT INTO ranking_snapshot "
                + "(snapshot_id, period_start, period_end, revision, status, input_fixed_at, input_count, product_count, "
                + "input_checksum, result_checksum, completed_at, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, 'READY', ?, ?, ?, ?, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)",
            manifest.snapshotId(), manifest.periodStart(), manifest.periodEnd(), manifest.revision(),
            manifest.inputFixedAt(), manifest.inputCount(), manifest.productCount(), manifest.inputChecksum());
        if (inserted != 1) {
            throw new IllegalStateException("ranking_snapshot INSERT 영향 행 수가 1이 아닙니다: " + inserted);
        }
    }
}
