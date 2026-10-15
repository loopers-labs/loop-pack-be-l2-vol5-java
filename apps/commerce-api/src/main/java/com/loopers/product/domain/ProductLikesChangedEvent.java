package com.loopers.product.domain;

/**
 * 이 상품의 좋아요 수가 바뀌었다는 사실 (W4). 좋아요 등록·취소가 발행한다 — like → product는 원래 허용된 방향이다.
 * 멱등 재요청(이미 있음·이미 없음)도 발행한다 — 바뀐 행 수를 알 수 없어서다 (docs/week4/plan.md 5장).
 */
public record ProductLikesChangedEvent(Long productId) {
}
