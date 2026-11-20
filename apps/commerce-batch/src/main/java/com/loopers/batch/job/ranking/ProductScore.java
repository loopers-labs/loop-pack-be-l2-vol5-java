package com.loopers.batch.job.ranking;

/** 고정 입력에서 계산한 한 상품의 주간 구매 수량 합. */
public record ProductScore(long productId, long score) {
    public ProductScore {
        if (productId <= 0 || score <= 0) {
            throw new IllegalArgumentException("상품 점수가 올바르지 않습니다: productId=" + productId + ", score=" + score);
        }
    }
}
