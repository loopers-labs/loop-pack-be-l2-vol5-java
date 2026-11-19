package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.domain.ProductScore;
import com.loopers.batch.job.ranking.domain.SnapshotId;
import com.loopers.batch.job.ranking.infrastructure.ScoreStageStore;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

public class ScoreStageWriter implements ItemWriter<ProductScore> {
    private final ScoreStageStore stages;
    private final SnapshotId snapshotId;

    public ScoreStageWriter(ScoreStageStore stages, SnapshotId snapshotId) {
        this.stages = stages;
        this.snapshotId = snapshotId;
    }

    @Override
    public void write(Chunk<? extends ProductScore> chunk) {
        stages.upsert(snapshotId, chunk.getItems());
    }
}
