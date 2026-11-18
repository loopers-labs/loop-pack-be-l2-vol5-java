package com.loopers.infrastructure.ranking.query;

import com.loopers.application.ranking.query.WeeklyRankingQueryRepository;
import com.loopers.application.ranking.query.WeeklyRankingView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 랭킹 테이블(TB-19·22·23)은 엔티티가 없어 native SQL 로 읽는다. 카탈로그(TB-02·03·08)는 클래스가 아니라 테이블로만 조인한다 (DR-71).
 * 고객 공개 조건은 상품 목록·상세와 같다: product.deleted_at IS NULL (브랜드 삭제는 소속 상품도 삭제한다, DR-33).
 * 공개 조건을 적용한 뒤 LIMIT 한다. 순위는 다시 매기지 않는다.
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingQueryRepositoryImpl implements WeeklyRankingQueryRepository {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<WeeklyRankingView.Publication> findPublication(LocalDate periodStart) {
        return jdbcTemplate.query("""
                select pub.snapshot_id, pub.revision, s.frozen_at
                from weekly_ranking_publication pub
                join ranking_snapshot s on s.snapshot_id = pub.snapshot_id
                where pub.period_start = ?
                """,
            (rs, n) -> new WeeklyRankingView.Publication(
                rs.getString("snapshot_id"), rs.getLong("revision"), rs.getObject("frozen_at", LocalDateTime.class)),
            periodStart).stream().findFirst();
    }

    @Override
    public List<WeeklyRankingView.Entry> findVisibleEntries(String snapshotId, int limit) {
        return jdbcTemplate.query("""
                select r.rank_no, r.score, p.id, p.name, p.price, b.id as brand_id, b.name as brand_name, st.like_count
                from weekly_ranking r
                join product p on p.id = r.product_id
                join brand b on b.id = p.brand_id
                join product_stat st on st.product_id = p.id
                where r.snapshot_id = ? and p.deleted_at is null
                order by r.rank_no
                limit ?
                """,
            (rs, n) -> new WeeklyRankingView.Entry(
                rs.getInt("rank_no"), rs.getLong("score"), rs.getLong("id"), rs.getString("name"), rs.getLong("price"),
                rs.getLong("brand_id"), rs.getString("brand_name"), rs.getLong("like_count")),
            snapshotId, limit);
    }
}
