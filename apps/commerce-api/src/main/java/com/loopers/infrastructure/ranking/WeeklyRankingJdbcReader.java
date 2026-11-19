package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.PublishedSnapshot;
import com.loopers.domain.ranking.RankedScore;
import com.loopers.domain.ranking.WeeklyRankingReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * weekly_ranking_* 테이블은 commerce-batch 가 정의를 소유한다. api 는 @Entity 를 두지 않고 JDBC 로 읽기만 한다 (설계 D14).
 * ②는 ①이 돌려준 snapshotId 로만 읽으므로 두 조회 사이에 pointer 가 바뀌어도 서로 다른 snapshot 의 행이 섞이지 않는다 (R5).
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingJdbcReader implements WeeklyRankingReader {

    /** ① pointer + COMPLETED 회차. */
    static final String FIND_PUBLISHED_SQL =
        "SELECT r.snapshot_id, r.revision, r.frozen_at "
            + "FROM weekly_ranking_publications p "
            + "JOIN weekly_ranking_revisions r ON r.snapshot_id = p.snapshot_id AND r.status = 'COMPLETED' "
            + "WHERE p.period_start = ?";

    /** ② 그 snapshot 의 완성 순위 행. */
    static final String FIND_RANKED_SCORES_SQL =
        "SELECT product_id, score, ranking FROM weekly_ranking_scores "
            + "WHERE snapshot_id = ? AND ranking IS NOT NULL ORDER BY ranking";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<PublishedSnapshot> findPublished(LocalDate periodStart) {
        return jdbcTemplate.query(FIND_PUBLISHED_SQL, (rs, rowNum) -> new PublishedSnapshot(
            rs.getString("snapshot_id"), rs.getInt("revision"), rs.getObject("frozen_at", LocalDateTime.class)
        ), periodStart).stream().findFirst();
    }

    @Override
    public List<RankedScore> findRankedScores(String snapshotId) {
        return jdbcTemplate.query(FIND_RANKED_SCORES_SQL, (rs, rowNum) -> new RankedScore(
            rs.getLong("product_id"), rs.getLong("score"), rs.getInt("ranking")
        ), snapshotId);
    }
}
