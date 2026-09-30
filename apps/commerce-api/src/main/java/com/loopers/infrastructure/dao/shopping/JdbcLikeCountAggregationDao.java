package com.loopers.infrastructure.dao.shopping;

import com.loopers.application.shopping.dao.LikeCountAggregationDao;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 좋아요 수 집계를 처리하는 JDBC DAO
public class JdbcLikeCountAggregationDao implements LikeCountAggregationDao {
    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    // 집계 카운트 전체 초기화
    @Override
    public void resetAllCounts() {
        jdbcClient.sql("UPDATE product_like_counts SET like_count = 0").update();
    }

    // 좋아요 수 전체 재집계
    @Override
    public void aggregateAllCounts() {
        jdbcClient.sql("""
                INSERT INTO product_like_counts (product_id, like_count)
                SELECT product_id, COUNT(*) FROM product_likes GROUP BY product_id
                ON DUPLICATE KEY UPDATE like_count = VALUES(like_count)
                """)
            .update();
    }

    // 상품별 증감분을 JDBC 배치 upsert로 반영
    @Override
    public void addDeltas(Map<Long, Long> deltas) {
        List<Object[]> args = new ArrayList<>();
        deltas.forEach((productId, delta) -> args.add(new Object[] {productId, delta, delta}));
        jdbcTemplate.batchUpdate("""
            INSERT INTO product_like_counts (product_id, like_count) VALUES (?, GREATEST(0, ?))
            ON DUPLICATE KEY UPDATE like_count = GREATEST(0, like_count + ?)
            """, args);
    }
}
