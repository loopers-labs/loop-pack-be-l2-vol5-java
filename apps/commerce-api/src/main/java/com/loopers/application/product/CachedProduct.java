package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

/**
 * 상품 키(product:{id})에 저장하는 값 — 상세와 목록이 함께 쓴다 (docs/week4/design.md 6번 섹션 "키와 값").
 * 브랜드명은 넣지 않는다: 브랜드 이름 변경 때 그 브랜드의 상품 키를 모두 지우지 않도록 brand:{id}로 분리한다.
 * likeCount는 무효화하지 않으므로 최대 TTL(60초)만큼 늦게 반영된다.
 */
public record CachedProduct(Long id, String name, Long price, int remainingStock, Long brandId, long likeCount) {

    public static CachedProduct of(ProductModel product, long likeCount) {
        return new CachedProduct(
            product.getId(),
            product.getName(),
            product.getPrice(),
            product.getRemainingStock(),
            product.getBrandId(),
            likeCount
        );
    }
}
