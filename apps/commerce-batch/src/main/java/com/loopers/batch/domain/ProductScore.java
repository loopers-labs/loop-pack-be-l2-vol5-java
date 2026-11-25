package com.loopers.batch.domain;

/**
 * 상품의 주간 점수 = 그 주 구매 수량 합 (W9 §1·D-4). writer가 절대값으로 덮어쓰므로 같은 상품을 두 번 써도 결과가 같다.
 */
public record ProductScore(long productId, long score) {

    /**
     * 수량 합이 0 이하이면 고정 입력이 잘못된 것이다. 다시 해도 같은 결과라 실패로 멈추고 사람이 원인을 본다 (D-7 실패 지도 #7).
     */
    public static ProductScore from(ProductTotal total) {
        if (total.total() <= 0) {
            throw new IllegalStateException("상품별 구매 수량 합이 0 이하입니다. [productId = " + total.productId() + ", total = " + total.total() + "]");
        }
        return new ProductScore(total.productId(), total.total());
    }
}
