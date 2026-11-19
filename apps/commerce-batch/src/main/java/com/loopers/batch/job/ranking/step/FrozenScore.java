package com.loopers.batch.job.ranking.step;

import org.springframework.batch.item.ItemCountAware;

/**
 * 고정한 점수 한 행. Reader가 읽을 때 몇 번째로 읽힌 아이템인지 넣어 준다.
 */
public final class FrozenScore implements ItemCountAware {

    private final long productId;
    private final long score;
    private int itemCount;

    public FrozenScore(long productId, long score) {
        this.productId = productId;
        this.score = score;
    }

    public long productId() {
        return productId;
    }

    public long score() {
        return score;
    }

    public int itemCount() {
        return itemCount;
    }

    @Override
    public void setItemCount(int count) {
        this.itemCount = count;
    }
}
