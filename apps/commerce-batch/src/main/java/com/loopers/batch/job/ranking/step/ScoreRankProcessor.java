package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductRank;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 몇 번째로 읽힌 점수인지를 계산 순위로 정한다. 순서는 Reader의 정렬이 정한다.
 */
@Component
public class ScoreRankProcessor implements ItemProcessor<FrozenScore, ProductRank> {

    @Override
    public ProductRank process(FrozenScore item) {
        return new ProductRank(item.productId(), item.itemCount());
    }
}
