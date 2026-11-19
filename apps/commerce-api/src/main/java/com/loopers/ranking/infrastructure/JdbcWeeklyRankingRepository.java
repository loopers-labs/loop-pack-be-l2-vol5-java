package com.loopers.ranking.infrastructure;

import com.loopers.ranking.domain.WeeklyRanking;
import com.loopers.ranking.domain.WeeklyRankingItem;
import com.loopers.ranking.domain.WeeklyRankingPeriod;
import com.loopers.ranking.domain.WeeklyRankingRepository;
import com.loopers.ranking.domain.WeeklyRankingStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcWeeklyRankingRepository implements WeeklyRankingRepository {

    private static final String FIND_RANKING = """
        select snapshot.snapshot_id, snapshot.input_fixed_at,
               visible.product_id, product.name as product_name, visible.score, visible.rank_no
        from (select cast(:periodStart as date) as period_start) requested
        left join weekly_ranking_publication publication
          on publication.period_start = requested.period_start
        left join weekly_ranking_snapshot snapshot
          on snapshot.snapshot_id = publication.snapshot_id
        left join weekly_ranking_item visible
          on visible.snapshot_id = publication.snapshot_id
         and exists (
            select 1 from product candidate
            join brand on brand.id = candidate.brand_id and brand.deleted_at is null
            where candidate.id = visible.product_id and candidate.deleted_at is null
         )
        left join product on product.id = visible.product_id
        order by visible.rank_no
        limit :limit
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWeeklyRankingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WeeklyRanking findByPeriod(WeeklyRankingPeriod period, int limit) {
        var parameters = new MapSqlParameterSource()
            .addValue("periodStart", period.start())
            .addValue("limit", limit);
        List<WeeklyRanking> rows = jdbc.query(FIND_RANKING, parameters, resultSet -> {
            if (!resultSet.next()) {
                return List.of();
            }
            String snapshotId = resultSet.getString("snapshot_id");
            LocalDateTime fixedAt = resultSet.getObject("input_fixed_at", LocalDateTime.class);
            List<WeeklyRankingItem> items = new ArrayList<>();
            do {
                long productId = resultSet.getLong("product_id");
                if (!resultSet.wasNull()) {
                    items.add(new WeeklyRankingItem(
                        productId,
                        resultSet.getString("product_name"),
                        resultSet.getLong("score"),
                        resultSet.getLong("rank_no")
                    ));
                }
            } while (resultSet.next());
            return List.of(new WeeklyRanking(
                snapshotId == null ? WeeklyRankingStatus.NOT_PUBLISHED : WeeklyRankingStatus.PUBLISHED,
                period.start(),
                period.end(),
                snapshotId,
                fixedAt == null ? null : fixedAt.toInstant(ZoneOffset.UTC),
                List.copyOf(items)
            ));
        });
        return rows.isEmpty()
            ? new WeeklyRanking(WeeklyRankingStatus.NOT_PUBLISHED, period.start(), period.end(), null, null, List.of())
            : rows.get(0);
    }
}
