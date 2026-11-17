package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ② 점수 계산의 processor. 상품 하나가 item 하나이고, 같은 snapshot 입력에서 그 상품의 수량 합을 계산한다.
 * 현재 구매 활동 테이블이 아니라 고정된 입력만 읽으므로, 재시작해도 같은 상품은 같은 점수가 나온다.
 */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
public class WeeklyScoreProcessor implements ItemProcessor<Long, ProductScore> {

    private final RankingSnapshotStore store;
    private final String snapshotId;

    public WeeklyScoreProcessor(RankingSnapshotStore store, @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        this.store = store;
        this.snapshotId = snapshotId;
    }

    @Override
    public ProductScore process(Long productId) {
        long score = store.sumQuantity(snapshotId, productId);
        if (score <= 0) {
            // 입력 고정에서 수량 0 이하를 막았으므로, 여기서 0이 나오면 입력 계약이 깨진 것이다. skip하지 않는다.
            throw new IllegalStateException("상품 점수가 0 이하입니다. snapshotId=" + snapshotId + ", productId=" + productId);
        }
        return new ProductScore(productId, score);
    }
}
