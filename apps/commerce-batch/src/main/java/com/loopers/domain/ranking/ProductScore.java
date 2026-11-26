package com.loopers.domain.ranking;

/** Step 2 의 chunk item: 한 상품의 주간 구매 수량 합(절대값). */
public record ProductScore(long productId, long score) {}
