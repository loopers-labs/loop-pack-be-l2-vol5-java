package com.loopers.domain.ranking;

/** 한 snapshot 에서 상품 하나의 구매 수량 합. */
public record ProductScore(Long productId, long score) {

    public ProductScore {
        // 고정 입력은 검증된 구매 사실이라 합이 1 미만이면 계약 위반이다. skip 하지 않고 드러낸다.
        if (score < 1) {
            throw new IllegalStateException("상품 " + productId + " 의 구매 수량 합이 1 미만입니다: " + score);
        }
    }
}
