package com.loopers.batch.job.ranking.infrastructure;

import com.loopers.batch.job.ranking.domain.ProductScore;
import com.loopers.batch.job.ranking.domain.SnapshotId;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

@Repository
public class ScoreStageStore {
    private final JdbcTemplate jdbc;

    public ScoreStageStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void upsert(SnapshotId snapshotId, List<? extends ProductScore> scores) {
        jdbc.batchUpdate("""
                INSERT INTO ranking_score_stage (snapshot_id, product_id, score) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE score = VALUES(score)
                """, scores, scores.size(), (ps, score) -> {
            ps.setString(1, snapshotId.value());
            ps.setLong(2, score.productId());
            ps.setLong(3, score.score());
        });
    }

    public int countScores(SnapshotId snapshotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ranking_score_stage WHERE snapshot_id = ?", Integer.class, snapshotId.value());
        return count == null ? 0 : count;
    }

    public int countMismatches(SnapshotId snapshotId) {
        String id = snapshotId.value();
        Integer mismatches = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*)
                   FROM (SELECT product_id, SUM(quantity) AS total
                         FROM ranking_snapshot_input WHERE snapshot_id = ? GROUP BY product_id) i
                   LEFT JOIN ranking_score_stage s ON s.snapshot_id = ? AND s.product_id = i.product_id
                   WHERE s.score IS NULL OR s.score <> i.total)
                + (SELECT COUNT(*)
                   FROM ranking_score_stage s
                   WHERE s.snapshot_id = ?
                     AND NOT EXISTS (SELECT 1 FROM ranking_snapshot_input i
                                     WHERE i.snapshot_id = s.snapshot_id AND i.product_id = s.product_id))
                """, Integer.class, id, id, id);
        return mismatches == null ? 0 : mismatches;
    }
}
