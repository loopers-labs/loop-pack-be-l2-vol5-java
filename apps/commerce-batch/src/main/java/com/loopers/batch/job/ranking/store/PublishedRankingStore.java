package com.loopers.batch.job.ranking.store;

import com.loopers.batch.job.ranking.WeeklySnapshot;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;

public class PublishedRankingStore {

    private final JdbcTemplate jdbc;

    public PublishedRankingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long productCount(String snapshotId) {
        return jdbc.queryForObject(
            "select count(distinct product_id) from ranking_snapshot_input where snapshot_id = ?",
            Long.class, snapshotId
        );
    }

    public long scoreCount(String snapshotId) {
        return jdbc.queryForObject(
            "select count(*) from ranking_score_stage where snapshot_id = ?", Long.class, snapshotId
        );
    }

    public boolean isRanked(String snapshotId) {
        return jdbc.queryForObject(
            "select count(*) from weekly_ranking where snapshot_id = ?", Long.class, snapshotId
        ) > 0;
    }

    public void rank(String snapshotId) {
        jdbc.update("""
            insert into weekly_ranking (snapshot_id, rank_no, product_id, score)
            select snapshot_id, row_number() over (order by score desc, product_id), product_id, score
            from ranking_score_stage
            where snapshot_id = ?
            """, snapshotId);
    }

    public void pointToUnlessNewerPublished(WeeklySnapshot snapshot) {
        jdbc.update("""
            insert into weekly_ranking_publication (period_start, snapshot_id, revision, published_at, frozen_at)
            select * from (
              select period_start, snapshot_id, revision, ? as published_at, frozen_at
              from ranking_snapshot
              where snapshot_id = ?
            ) as candidate
            on duplicate key update
              snapshot_id = if(candidate.revision > weekly_ranking_publication.revision,
                               candidate.snapshot_id, weekly_ranking_publication.snapshot_id),
              published_at = if(candidate.revision > weekly_ranking_publication.revision,
                                candidate.published_at, weekly_ranking_publication.published_at),
              frozen_at = if(candidate.revision > weekly_ranking_publication.revision,
                             candidate.frozen_at, weekly_ranking_publication.frozen_at),
              revision = greatest(candidate.revision, weekly_ranking_publication.revision)
            """,
            LocalDateTime.now(ZoneOffset.UTC), snapshot.id()
        );
    }

    public String resultChecksum(String snapshotId) {
        return new SortedRowsChecksum(jdbc).of("""
            select product_id, score, rank_no
            from weekly_ranking
            where snapshot_id = ?
            order by rank_no
            """, snapshotId,
            rs -> rs.getLong("product_id") + "\t" + rs.getLong("score") + "\t" + rs.getInt("rank_no")
        );
    }
}
