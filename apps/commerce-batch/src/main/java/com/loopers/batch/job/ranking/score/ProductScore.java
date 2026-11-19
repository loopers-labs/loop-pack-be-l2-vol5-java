package com.loopers.batch.job.ranking.score;

/** 고정 입력에서 계산한 상품의 주간 절대 점수 */
public record ProductScore(String snapshotId, long productId, long score) {}
