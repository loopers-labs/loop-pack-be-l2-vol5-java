package com.loopers.batch.job.ranking;

/** 고정한 snapshot 입력에서 계산한 상품 하나의 절대 점수 (quantity 합). chunk item 하나. */
public record ProductScore(long productId, long score) {
}
