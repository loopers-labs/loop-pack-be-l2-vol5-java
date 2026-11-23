package com.loopers.infrastructure.ranking;

import com.loopers.application.ranking.PublishedRanking;
import com.loopers.application.ranking.PublishedRanking.RankedProduct;
import com.loopers.application.ranking.WeeklyRankingQuery;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WeeklyRankingQueryImpl implements WeeklyRankingQuery {
    private static final String PUBLISHED_RANKING = """
            SELECT pub.snapshot_id, s.frozen_at,
                   r.rank_no, r.product_id, r.score, p.name, p.price, b.id AS brand_id, b.name AS brand_name
            FROM ranking_publication pub
            JOIN ranking_snapshot s ON s.snapshot_id = pub.snapshot_id
            LEFT JOIN (ranking_result r
                       JOIN products p ON p.id = r.product_id AND p.deleted_at IS NULL
                       JOIN brands b ON b.id = p.brand_id AND b.deleted_at IS NULL)
                   ON r.snapshot_id = pub.snapshot_id
            WHERE pub.period_start = ?
            ORDER BY r.rank_no
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    @Override
    public Optional<PublishedRanking> findPublished(LocalDate periodStart, int limit) {
        List<Row> rows = jdbc.query(PUBLISHED_RANKING, (rs, n) -> new Row(
                rs.getString("snapshot_id"),
                rs.getObject("frozen_at", LocalDateTime.class),
                rs.getObject("product_id") == null ? null : new RankedProduct(
                        rs.getInt("rank_no"), rs.getLong("product_id"), rs.getLong("score"), rs.getString("name"),
                        rs.getLong("price"), rs.getLong("brand_id"), rs.getString("brand_name"))),
                periodStart, limit);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row header = rows.getFirst();
        List<RankedProduct> items = rows.stream().map(Row::product).filter(Objects::nonNull).toList();
        return Optional.of(new PublishedRanking(header.snapshotId(), header.frozenAt().toInstant(ZoneOffset.UTC), items));
    }

    private record Row(String snapshotId, LocalDateTime frozenAt, RankedProduct product) {
    }
}
