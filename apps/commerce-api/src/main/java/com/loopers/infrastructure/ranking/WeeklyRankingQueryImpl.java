package com.loopers.infrastructure.ranking;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.loopers.domain.ranking.RankingLimit;
import com.loopers.domain.ranking.WeekStart;
import com.loopers.domain.ranking.WeeklyRankingQuery;

@Repository
class WeeklyRankingQueryImpl implements WeeklyRankingQuery {

    private static final String PUBLISHED_RANKING = """
        SELECT pub.snapshot_id, pub.frozen_at,
               v.rank_no, v.product_id, v.score, v.product_name, v.price, v.quantity, v.brand_id, v.brand_name
          FROM weekly_ranking_publication pub
          LEFT JOIN (
                SELECT r.snapshot_id, r.rank_no, r.product_id, r.score,
                       p.name AS product_name, p.price, p.quantity, b.id AS brand_id, b.name AS brand_name
                  FROM weekly_ranking r
                  JOIN product p ON p.id = r.product_id AND p.deleted_at IS NULL
                  JOIN brand b ON b.id = p.brand_id
               ) v ON v.snapshot_id = pub.snapshot_id
         WHERE pub.period_start = :periodStart
         ORDER BY v.rank_no
         LIMIT :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;

    WeeklyRankingQueryImpl(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Optional<Published> findPublished(WeekStart week, RankingLimit limit) {
        return jdbc.query(PUBLISHED_RANKING, Map.of("periodStart", week.value(), "limit", limit.value()), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            String snapshotId = rs.getString("snapshot_id");
            LocalDateTime frozenAt = rs.getObject("frozen_at", LocalDateTime.class);
            List<Item> items = new ArrayList<>();
            do {
                if (rs.getObject("rank_no") != null) {
                    items.add(new Item(
                        rs.getInt("rank_no"),
                        rs.getLong("product_id"),
                        rs.getLong("score"),
                        rs.getString("product_name"),
                        rs.getLong("brand_id"),
                        rs.getString("brand_name"),
                        rs.getLong("price"),
                        rs.getInt("quantity") == 0
                    ));
                }
            } while (rs.next());
            return Optional.of(new Published(snapshotId, frozenAt, items));
        });
    }
}
