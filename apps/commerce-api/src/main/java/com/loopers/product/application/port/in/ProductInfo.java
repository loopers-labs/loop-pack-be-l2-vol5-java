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
}
