package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.domain.ProductScore;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.infrastructure.RankingSnapshotStore;

import org.springframework.batch.item.ItemProcessor;

public class WeeklyScoreProcessor implements ItemProcessor<Long, ProductScore> {
    private final RankingSnapshotStore snapshots;
    private final SnapshotId snapshotId;

    public WeeklyScoreProcessor(RankingSnapshotStore snapshots, SnapshotId snapshotId) {
        this.snapshots = snapshots;
        this.snapshotId = snapshotId;
    }

    @Override
    public ProductScore process(Long productId) {
        return snapshots.productTotal(snapshotId, productId).toScore();
    }
}
