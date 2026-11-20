package com.loopers.batch.job.ranking.infrastructure;

import com.loopers.batch.job.ranking.domain.PublishedSnapshot;
import com.loopers.batch.job.ranking.domain.RankingChecksum;
import com.loopers.batch.job.ranking.domain.RankingPeriod;
import com.loopers.batch.job.ranking.domain.SnapshotId;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RankingPublicationStore {
    private final JdbcTemplate jdbc;
    private final JdbcTemplate streamingJdbc;

    public RankingPublicationStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc.setFetchSize(Integer.MIN_VALUE);
    }

    public int insertRanking(SnapshotId snapshotId) {
        return jdbc.update("""
                INSERT INTO ranking_result (snapshot_id, product_id, score, rank_no)
                SELECT snapshot_id, product_id, score,
                       ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC)
                FROM ranking_score_stage WHERE snapshot_id = ?
                """, snapshotId.value());
    }

    public String resultChecksum(SnapshotId snapshotId) {
        var checksum = RankingChecksum.start();
        streamingJdbc.query(
                "SELECT product_id, score, rank_no FROM ranking_result WHERE snapshot_id = ? ORDER BY rank_no",
                rs -> {
                    checksum.resultLine(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("rank_no"));
                }, snapshotId.value());
        return checksum.hex();
    }

    public Optional<PublishedSnapshot> findPublication(RankingPeriod period) {
        List<PublishedSnapshot> rows = jdbc.query(
                "SELECT snapshot_id, revision FROM ranking_publication WHERE period_start = ?",
                (rs, n) -> new PublishedSnapshot(rs.getString("snapshot_id"), rs.getInt("revision")),
                period.start());
        return rows.stream().findFirst();
    }

    // ON DUPLICATE KEY UPDATE는 왼쪽부터 대입하므로 revision을 마지막에 바꾼다.
    public boolean publishIfNewer(SnapshotId snapshotId, LocalDateTime publishedAtUtc) {
        jdbc.update("""
                INSERT INTO ranking_publication (period_start, snapshot_id, revision, published_at)
                VALUES (?, ?, ?, ?) AS candidate
                ON DUPLICATE KEY UPDATE
                  snapshot_id = IF(candidate.revision > ranking_publication.revision,
                                   candidate.snapshot_id, ranking_publication.snapshot_id),
                  published_at = IF(candidate.revision > ranking_publication.revision,
                                    candidate.published_at, ranking_publication.published_at),
                  revision = GREATEST(ranking_publication.revision, candidate.revision)
                """, snapshotId.period().start(), snapshotId.value(), snapshotId.revision(), publishedAtUtc);
        String current = jdbc.queryForObject(
                "SELECT snapshot_id FROM ranking_publication WHERE period_start = ?",
                String.class, snapshotId.period().start());
        return snapshotId.value().equals(current);
    }
}
