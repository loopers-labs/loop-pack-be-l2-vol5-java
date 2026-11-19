package com.loopers.batch.job.ranking.score;

/** 점수 계산 chunk 의 item. 상품 하나가 item 하나다 (실습 §4) */
public record ProductKey(String snapshotId, long productId) {}
