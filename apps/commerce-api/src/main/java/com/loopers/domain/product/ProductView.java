package com.loopers.domain.product;

/**
 * 고객 상품 조회 결과 타입 (설계 4.5). 좋아요 수는 관계에서 집계한 값이다.
 * 재고는 수량 그대로 담고, 품절 여부로 바꾸는 일은 응답을 만들 때 한다.
 */
public record ProductView(
    Long id,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    String brandDescription
) {}
