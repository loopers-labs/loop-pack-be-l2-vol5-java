package com.loopers.batch.domain;

/**
 * 점수 계산 step의 item — 고정 입력에서 상품 하나의 구매 수량 합 (W9 D-4). reader가 GROUP BY로 만든다.
 */
public record ProductTotal(long productId, long total) {
}
