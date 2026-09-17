package com.loopers.domain.product;

/**
 * 상품 목록·상세 화면에 필요한 값을 한 번의 읽기 전용 조회로 조합한 결과.
 * brandName 과 likeCount 는 조회 시 조합·집계되는 스칼라 값이며 ProductModel 의 영속 상태가 아니다.
 */
public record ProductQueryResult(
    Long id,
    Long brandId,
    String brandName,
    String name,
    long price,
    long likeCount,
    long stockQuantity
) {
}
