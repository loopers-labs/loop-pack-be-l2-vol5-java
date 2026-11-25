package com.loopers.batch.job.weeklyranking;

import com.loopers.batch.domain.ProductScore;
import com.loopers.batch.domain.ProductTotal;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 점수 계산 step의 processor (W9 D-4). 상품 하나의 수량 합 → 점수 행. 상태가 없어 재시작해도 같은 값을 낸다.
 */
@Component
public class WeeklyScoreProcessor implements ItemProcessor<ProductTotal, ProductScore> {

    @Override
    public ProductScore process(ProductTotal item) {
        return ProductScore.from(item);
    }
}
