package com.loopers.infrastructure.ranking;

import com.loopers.application.ranking.WeeklyRankingQueryException;
import com.loopers.domain.ranking.PublishedRanking;
import com.loopers.domain.ranking.PublishedRankingRepository;
import com.loopers.domain.ranking.RankingCandidate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.DateTimeException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPublishedRankingRepository implements PublishedRankingRepository {
    private final RankingReadResource resource;

    public JdbcPublishedRankingRepository(@Qualifier("weeklyRankingReadResource") RankingReadResource resource) {
        this.resource = resource;
    }

    @Override
    public Optional<PublishedRanking> findPublished(LocalDate periodStart) {
        // One statement selects the pointer and its manifest together, even while a new revision is published.
        var rows = resource.jdbc().query("""
            SELECT p.snapshot_id AS published_id, p.revision AS published_revision, s.*,
                (SELECT COUNT(*) FROM weekly_ranking_result r WHERE r.snapshot_id=p.snapshot_id) AS stored_count,
                (SELECT MIN(ranking_position) FROM weekly_ranking_result r WHERE r.snapshot_id=p.snapshot_id) AS first_rank,
                (SELECT MAX(ranking_position) FROM weekly_ranking_result r WHERE r.snapshot_id=p.snapshot_id) AS last_rank
            FROM weekly_ranking_publication p
            LEFT JOIN weekly_ranking_snapshot s ON s.snapshot_id=p.snapshot_id
            WHERE p.period_start=?
            """, (row, number) -> header(row, periodStart), periodStart);
        if (rows.size() > 1) {
            throw corrupt();
        }
        return rows.stream().findFirst();
    }

    @Override
    public List<RankingCandidate> findCandidates(String snapshotId, long afterRank, int size) {
        return resource.jdbc().query("""
            SELECT product_id, score, ranking_position FROM weekly_ranking_result
            WHERE snapshot_id=? AND ranking_position>? ORDER BY ranking_position ASC LIMIT ?
            """, (row, number) -> new RankingCandidate(row.getLong("product_id"), row.getLong("score"),
                row.getLong("ranking_position")), snapshotId, afterRank, size);
    }

    private PublishedRanking header(ResultSet row, LocalDate expectedWeek) throws SQLException {
        String snapshotId = row.getString("snapshot_id");
        var start = row.getObject("period_start", LocalDate.class);
        var end = row.getObject("period_end", LocalDate.class);
        Long count = row.getObject("result_count", Long.class);
        int revision = row.getInt("revision");
        String checksum = row.getString("result_checksum");
        if (snapshotId == null || !snapshotId.equals(row.getString("published_id"))
            || !expectedWeek.equals(start) || start.getDayOfWeek() != DayOfWeek.MONDAY
            || !start.plusDays(7).equals(end) || revision < 1 || revision != row.getInt("published_revision")
            || !"FROZEN".equals(row.getString("input_state")) || !"COMPLETE".equals(row.getString("calculation_state"))
            || count == null || count < 0 || count != row.getLong("product_count")
            || count != row.getLong("stored_count") || !"v1".equals(row.getString("result_checksum_version"))
            || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw corrupt();
        }
        if (count > 0 && (row.getLong("first_rank") != 1 || row.getLong("last_rank") != count)) {
            throw corrupt();
        }
        Long frozenSecond = row.getObject("frozen_at_second", Long.class);
        Integer frozenNano = row.getObject("frozen_at_nano", Integer.class);
        if (frozenSecond == null || frozenNano == null || frozenNano < 0 || frozenNano > 999999999) {
            throw corrupt();
        }
        try {
            return new PublishedRanking(snapshotId, start, end, revision, Instant.ofEpochSecond(frozenSecond, frozenNano), count);
        } catch (DateTimeException exception) {
            throw corrupt();
        }
    }

    private WeeklyRankingQueryException corrupt() {
        return new WeeklyRankingQueryException(WeeklyRankingQueryException.Reason.CORRUPT);
    }
}
