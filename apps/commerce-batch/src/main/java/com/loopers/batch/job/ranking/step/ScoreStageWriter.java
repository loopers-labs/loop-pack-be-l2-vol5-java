package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/** chunk의 상품별 점수를 reader 위치와 같은 트랜잭션에서 한 번 삽입한다. */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@RequiredArgsConstructor
@Component
public class ScoreStageWriter implements ItemWriter<ProductScore> {
    private final WeeklyRankingStore store;

    @Value("#{jobParameters['snapshotId']}")
    private String snapshotId;

    @Override
    public void write(Chunk<? extends ProductScore> chunk) {
        store.insertScores(snapshotId, new ArrayList<>(chunk.getItems()));
    }
}
