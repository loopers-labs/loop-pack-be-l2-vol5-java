package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.VisibleRankingProducts;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Repository
public class JdbcVisibleRankingProducts implements VisibleRankingProducts {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcVisibleRankingProducts(@Qualifier("mySqlMainDataSource") DataSource mainDataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(mainDataSource);
    }

    @Override
    public Map<Long, String> names(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("""
            SELECT p.id, p.name FROM product p JOIN brand b ON b.id=p.brand_id
            WHERE p.id IN (:ids) AND p.deleted_at IS NULL AND b.deleted_at IS NULL
            """, Map.of("ids", productIds), result -> {
                Map<Long, String> names = new HashMap<>();
                while (result.next()) {
                    names.put(result.getLong("id"), result.getString("name"));
                }
                return Map.copyOf(names);
            });
    }
}
