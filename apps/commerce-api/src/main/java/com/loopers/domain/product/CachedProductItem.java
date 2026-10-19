package com.loopers.domain.product;

/**
 * 상품 목록 캐시에 저장하는 상품 한 건. 재고는 조회 시점마다 DB 에서 읽으므로 담지 않는다.
 */
public record CachedProductItem(
    Long productId,
    String productName,
    long price,
    Long brandId,
    String brandName,
    long likeCount
) {
}
