package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ② 점수 계산의 writer. (snapshotId, productId)에 절대 점수를 upsert한다.
 * chunk의 트랜잭션에 참여하므로 점수와 reader checkpoint가 함께 commit되고, commit 전에 실패한 chunk를 다시 처리해도 score = total로 수렴한다.
 */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = WeeklyRankingJobConfig.JOB_NAME)
@Component
public class WeeklyScoreWriter implements ItemWriter<ProductScore> {

    private final RankingSnapshotStore store;
    private final String snapshotId;

    public WeeklyScoreWriter(RankingSnapshotStore store, @Value("#{jobParameters['snapshotId']}") String snapshotId) {
        this.store = store;
        this.snapshotId = snapshotId;
    }

    @Override
    public void write(Chunk<? extends ProductScore> chunk) {
        List<ProductScore> scores = List.copyOf(chunk.getItems());
        store.upsertScores(snapshotId, scores);
    }
}
