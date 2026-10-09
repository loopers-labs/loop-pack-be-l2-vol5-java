package com.loopers.infrastructure.dao.shopping;

import com.loopers.application.shopping.dao.LikeCountAggregationDao;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    // 좋아요 수 전체 재집계 (값이 달라진 상품만 갱신, updated_at은 건드리지 않음)
    @Override
    public void recountAll() {
        jdbcClient.sql("""
                UPDATE products p
                LEFT JOIN (SELECT product_id, COUNT(*) AS c FROM product_likes GROUP BY product_id) a
                    ON a.product_id = p.id
                SET p.like_count = COALESCE(a.c, 0)
                WHERE p.like_count <> COALESCE(a.c, 0)
                """)
            .update();
    }

    // 상품별 증감분을 상품 id 오름차순 JDBC 배치 UPDATE로 반영 (주문 확정의 상품 잠금 순서와 맞춤)
    @Override
    public void addDeltas(Map<Long, Long> deltas) {
        List<Object[]> args = new ArrayList<>();
        new TreeMap<>(deltas).forEach((productId, delta) -> args.add(new Object[] {delta, productId}));
        jdbcTemplate.batchUpdate("UPDATE products SET like_count = GREATEST(0, like_count + ?) WHERE id = ?", args);
    }
}
