package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 상품 하나의 점수를 고정 입력의 구매 수량 합으로 계산한다. */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class SnapshotScoreProcessor implements ItemProcessor<Long, ProductScore> {
    private final WeeklyRankingStore store;

    @Value("#{jobParameters['snapshotId']}")
    private String snapshotId;

    @Override
    public ProductScore process(Long productId) {
        return new ProductScore(productId, store.productQuantityTotal(snapshotId, productId));
    }
}
