package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.CurrentPublication;
import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.RankingPublicationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class JdbcRankingPublicationRepository implements RankingPublicationRepository {

    private final JdbcTemplate jdbc;

    public JdbcRankingPublicationRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<RankingInputManifest> findSnapshot(String snapshotId) {
        return jdbc.query(RankingSnapshotRows.SELECT + "WHERE snapshot_id = ?", RankingSnapshotRows.MAPPER, snapshotId)
            .stream().findFirst();
    }

    @Override
    public Optional<CurrentPublication> findCurrentPublication(LocalDate periodStart, boolean forUpdate) {
        return jdbc.query("SELECT publication.period_start, "
                + "publication.deleted_at AS pointer_deleted_at, snapshot.snapshot_id, "
                + "snapshot.period_start AS snapshot_period_start, snapshot.revision, snapshot.status, "
                + "snapshot.result_checksum, snapshot.completed_at, snapshot.deleted_at AS snapshot_deleted_at "
                + "FROM ranking_publication AS publication "
                + "LEFT JOIN ranking_snapshot AS snapshot ON snapshot.snapshot_id = publication.published_snapshot_id "
                + "WHERE publication.period_start = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, rowNum) -> new CurrentPublication(
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("pointer_deleted_at") != null,
                rs.getString("snapshot_id"),
                rs.getObject("snapshot_period_start", LocalDate.class),
                rs.getObject("revision", Long.class),
                rs.getString("status"),
                rs.getString("result_checksum"),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("snapshot_deleted_at") != null),
            periodStart).stream().findFirst();
    }

    @Override
    public long countResults(String snapshotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ranking_result WHERE snapshot_id = ?", Long.class, snapshotId);
    }

    @Override
    public PreparedCounts countPreparedProducts(String snapshotId) {
        return jdbc.queryForObject("SELECT COUNT(*) AS stage_count, COUNT(expected.product_id) AS prepared_product_count "
                + "FROM ranking_score_stage AS stage "
                + "LEFT JOIN (SELECT DISTINCT product_id FROM ranking_snapshot_input WHERE snapshot_id = ?) AS expected "
                + "ON expected.product_id = stage.product_id WHERE stage.snapshot_id = ?",
            (rs, rowNum) -> new PreparedCounts(rs.getLong("stage_count"), rs.getLong("prepared_product_count")),
            snapshotId, snapshotId);
    }

    @Override
    public int insertRankedResults(String snapshotId) {
        return jdbc.update("INSERT INTO ranking_result "
            + "(snapshot_id, product_id, score, calculated_rank, created_at, updated_at, deleted_at) "
            + "SELECT stage.snapshot_id, stage.product_id, stage.score, "
            + "ROW_NUMBER() OVER (ORDER BY stage.score DESC, stage.product_id ASC), "
            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL "
            + "FROM ranking_score_stage AS stage WHERE stage.snapshot_id = ?", snapshotId);
    }

    @Override
    public void forEachRankedResult(String snapshotId, RankedResultHandler handler) {
        jdbc.query("SELECT product_id, score, calculated_rank FROM ranking_result "
                + "WHERE snapshot_id = ? ORDER BY calculated_rank",
            (RowCallbackHandler) rs -> handler.accept(rs.getLong("product_id"),
                rs.getBigDecimal("score").toBigIntegerExact(), rs.getLong("calculated_rank")), snapshotId);
    }

    @Override
    public int markCompleted(String snapshotId, LocalDate periodStart, long revision, String resultChecksum) {
        return jdbc.update("UPDATE ranking_snapshot SET status = 'COMPLETED', result_checksum = ?, "
                + "completed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) "
                + "WHERE snapshot_id = ? AND period_start = ? AND revision = ? AND status = 'READY' AND deleted_at IS NULL",
            resultChecksum, snapshotId, periodStart, revision);
    }

    @Override
    public int publishIfNewer(String snapshotId, LocalDate periodStart) {
        return jdbc.update("UPDATE ranking_publication AS publication "
                + "JOIN ranking_snapshot AS current_snapshot ON current_snapshot.snapshot_id = publication.published_snapshot_id "
                + "JOIN ranking_snapshot AS target_snapshot ON target_snapshot.snapshot_id = ? "
                + "SET publication.published_snapshot_id = target_snapshot.snapshot_id, "
                + "publication.published_at = UTC_TIMESTAMP(6), publication.updated_at = UTC_TIMESTAMP(6) "
                + "WHERE publication.period_start = ? "
                + "AND current_snapshot.period_start = publication.period_start "
                + "AND target_snapshot.period_start = publication.period_start "
                + "AND current_snapshot.status = 'COMPLETED' AND target_snapshot.status = 'COMPLETED' "
                + "AND publication.deleted_at IS NULL AND current_snapshot.deleted_at IS NULL "
                + "AND target_snapshot.deleted_at IS NULL "
                + "AND target_snapshot.revision > current_snapshot.revision",
            snapshotId, periodStart);
    }

    @Override
    public int insertPublication(LocalDate periodStart, String snapshotId) {
        return jdbc.update("INSERT INTO ranking_publication "
                + "(period_start, published_snapshot_id, published_at, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)",
            periodStart, snapshotId);
    }
}
