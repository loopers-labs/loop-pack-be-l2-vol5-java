package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** ranking_publication·ranking_snapshot·ranking_entry를 SQL로 읽는다. 엔티티로 매핑하지 않는다(ADR 0080). */
@Repository
public class JdbcWeeklyRankingReader implements WeeklyRankingReader {
    private final JdbcTemplate jdbc;

    public JdbcWeeklyRankingReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<PublishedSnapshot> findPublished(LocalDate periodStart) {
        return jdbc.query("""
            SELECT p.snapshot_id, p.revision, s.frozen_at
            FROM ranking_publication p
            JOIN ranking_snapshot s ON s.snapshot_id = p.snapshot_id
            WHERE p.period_start = ?
            """, (rs, n) -> new PublishedSnapshot(
                rs.getString("snapshot_id"),
                rs.getInt("revision"),
                // 배치가 UTC 값으로 저장한다.
                rs.getObject("frozen_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
            periodStart).stream().findFirst();
    }

    @Override
    public List<Entry> findEntries(String snapshotId, int offset, int limit) {
        return jdbc.query("""
            SELECT product_id, score, ranking FROM ranking_entry
            WHERE snapshot_id = ?
            ORDER BY ranking
            LIMIT ? OFFSET ?
            """, (rs, n) -> new Entry(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("ranking")),
            snapshotId, limit, offset);
    }
}
