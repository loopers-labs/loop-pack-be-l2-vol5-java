package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import org.springframework.batch.item.ItemProcessor;

/** 상품 하나 → 같은 frozen snapshot 에서 계산한 quantity 합. 현재 활동 테이블은 읽지 않는다. step scope 로 snapshot 마다 만든다. */
public class WeeklyScoreProcessor implements ItemProcessor<Long, ProductScore> {
    private final WeeklyRankingStore store;
    private final String snapshotId;

    public WeeklyScoreProcessor(WeeklyRankingStore store, String snapshotId) {
        this.store = store;
        this.snapshotId = snapshotId;
    }

    @Override
    public ProductScore process(Long productId) {
        long score = store.sumQuantity(snapshotId, productId);
        if (score <= 0) {
            throw new IllegalStateException("고정 입력에서 읽은 상품의 점수가 0 입니다. [snapshotId = " + snapshotId + ", productId = " + productId + "]");
        }
        return new ProductScore(productId, score);
    }
}
