package com.loopers.batch.job.ranking;

/** 한 상품의 주간 절대 점수(그 snapshot 입력의 수량 합). */
public record ProductScore(long productId, long score) {
}
