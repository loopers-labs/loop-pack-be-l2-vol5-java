package com.loopers.infrastructure.product;

import com.loopers.domain.brand.BrandName;
import com.loopers.domain.product.ProductSummary;

import java.time.ZonedDateTime;

/** 좋아요 집계와 분리해 읽는 저장소 내부 상품 상세 조회 결과다. */
public record ProductPageRow(Long productId, String name, long price, int stockQuantity,
                             Long brandId, BrandName brandName, ZonedDateTime createdAt,
                             ZonedDateTime updatedAt, ZonedDateTime deletedAt) {

    ProductSummary toSummary(long likeCount) {
        return new ProductSummary(productId, name, price, stockQuantity, brandId, brandName, likeCount,
            createdAt, updatedAt, deletedAt);
    }
}
