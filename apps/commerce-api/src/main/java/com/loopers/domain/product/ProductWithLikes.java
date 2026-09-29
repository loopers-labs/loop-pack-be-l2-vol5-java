package com.loopers.domain.product;

/**
 * 조회 결과: 상품과 그 상품의 좋아요 수 (LIK-04).
 */
public record ProductWithLikes(ProductModel product, long likeCount) {}
