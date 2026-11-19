package com.loopers.batch.job.ranking.score;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 현재 활동 테이블이 아니라 고정한 snapshot 입력에서 상품의 수량 합을 구한다 (실습 §4) */
@RequiredArgsConstructor
@Component
public class WeeklyScoreProcessor implements ItemProcessor<ProductKey, ProductScore> {

    private final JdbcTemplate jdbc;

    @Override
    public ProductScore process(ProductKey key) {
        Long total = jdbc.queryForObject(
            "select sum(quantity) from weekly_ranking_input where snapshot_id = ? and product_id = ?",
            Long.class, key.snapshotId(), key.productId());
        if (total == null) {
            // reader 가 같은 입력에서 읽은 상품이라 합이 없을 수 없다. 조용히 0 으로 두지 않는다
            throw new IllegalStateException("고정 입력에 상품이 없음. key=" + key);
        }
        return new ProductScore(key.snapshotId(), key.productId(), total);
    }
}
