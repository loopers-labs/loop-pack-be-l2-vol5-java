package com.loopers.batch.job.ranking;

/**
 * 상품 하나에 매긴 계산 순위.
 */
public record ProductRank(long productId, int rank) {
}
