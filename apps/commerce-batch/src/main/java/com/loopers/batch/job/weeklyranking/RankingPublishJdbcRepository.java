package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.SnapshotStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 공개 step의 SQL (W9 D-5). 공개 step의 TX 하나 안에서 불린다. 시각은 UTC 벽시계 값(LocalDateTime)으로 바인딩한다.
 */
@Component
public class RankingPublishJdbcRepository {

    private final JdbcTemplate jdbc;

    public RankingPublishJdbcRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public long countScores(String snapshotId) {
        return count("SELECT COUNT(*) FROM weekly_ranking WHERE snapshot_id = ?", snapshotId);
    }

    public long countInputProducts(String snapshotId) {
        return count("SELECT COUNT(DISTINCT product_id) FROM ranking_snapshot_input WHERE snapshot_id = ?", snapshotId);
    }

    /**
     * 점수 큰 순, 같으면 상품 ID 작은 순으로 1부터 매긴다. 같은 snapshot을 다시 공개해도 같은 값이 들어간다.
     */
    public int assignRanks(String snapshotId, LocalDateTime nowUtc) {
        return jdbc.update("""
            UPDATE weekly_ranking w
              JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC) AS rn
                      FROM weekly_ranking
                     WHERE snapshot_id = ?) r ON r.id = w.id
               SET w.rank_no = r.rn, w.updated_at = ?
            """, snapshotId, nowUtc);
    }

    public void markCompleted(String snapshotId, LocalDateTime nowUtc) {
        int updated = jdbc.update("UPDATE ranking_snapshot SET status = ?, updated_at = ? WHERE snapshot_id = ?",
            SnapshotStatus.COMPLETED.name(), nowUtc, snapshotId);
        if (updated != 1) {
            throw new IllegalStateException("공개할 snapshot이 없습니다. [snapshotId = " + snapshotId + "]");
        }
    }

    /**
     * 그 주 pointer 행을 잠그고 읽는다. 같은 주의 공개 step 둘이 겹쳐도 하나씩 비교·교체한다 (D-5).
     */
    public Optional<Publication> lockPublication(LocalDate periodStart) {
        List<Publication> found = jdbc.query(
            "SELECT snapshot_id, revision FROM ranking_publication WHERE period_start = ? FOR UPDATE",
            (rs, rowNum) -> new Publication(rs.getString("snapshot_id"), rs.getLong("revision")),
            periodStart);
        return found.stream().findFirst();
    }

    public void insertPublication(LocalDate periodStart, String snapshotId, long revision, LocalDateTime nowUtc) {
        jdbc.update("""
            INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, periodStart, snapshotId, revision, nowUtc, nowUtc, nowUtc);
    }

    public void updatePublication(LocalDate periodStart, String snapshotId, long revision, LocalDateTime nowUtc) {
        jdbc.update("""
            UPDATE ranking_publication
               SET snapshot_id = ?, revision = ?, published_at = ?, updated_at = ?
             WHERE period_start = ?
            """, snapshotId, revision, nowUtc, nowUtc, periodStart);
    }

    private long count(String sql, String snapshotId) {
        Long count = jdbc.queryForObject(sql, Long.class, snapshotId);
        return count == null ? 0 : count;
    }

    public record Publication(String snapshotId, long revision) {}
}
