package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.WeeklyRankingStore;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/** chunk 의 상품 점수를 (snapshotId, productId) 에 절대값으로 upsert 한다. chunk TX 에 참여해 reader checkpoint 와 같이 commit 된다. */
public class ScoreStageWriter implements ItemWriter<ProductScore> {
    private final WeeklyRankingStore store;
    private final String snapshotId;

    public ScoreStageWriter(WeeklyRankingStore store, String snapshotId) {
        this.store = store;
        this.snapshotId = snapshotId;
    }

    @Override
    public void write(Chunk<? extends ProductScore> chunk) {
        store.upsertScores(snapshotId, chunk.getItems());
    }
}
