package com.loopers.product.application.port.in;

import com.loopers.brand.domain.BrandModel;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductWithLikes;

/**
 * 고객 상품 조회 결과: 상품 + 브랜드 + 좋아요 수 (ADR-02).
 */
public record ProductInfo(Long id, String name, long price, Long brandId, String brandName, long likeCount) {
    public static ProductInfo of(ProductWithLikes productWithLikes, BrandModel brand) {
        ProductModel product = productWithLikes.product();
        return new ProductInfo(
            product.getId(),
            product.getName(),
            product.getPrice().amount(),
            product.getBrandId(),
            brand == null ? null : brand.getName(),
            productWithLikes.likeCount()
        );
    }

    /**
     * W4 목록 캐시 hit: 순서·브랜드·좋아요 수는 캐시 값 그대로 두고, 상품의 이름·가격만 원본 값으로 바꾼다.
     */
    public ProductInfo withSourceOf(ProductModel product) {
        return new ProductInfo(id, product.getName(), product.getPrice().amount(), brandId, brandName, likeCount);
    }
}
