package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.product.ProductSummary;

import java.time.ZonedDateTime;

public record AdminProductQueryInfo(Long productId, String name, long price, int stockQuantity,
                                   BrandInfo brand, long likeCount, ZonedDateTime createdAt,
                                   ZonedDateTime updatedAt, ZonedDateTime deletedAt) {

    public static AdminProductQueryInfo from(ProductSummary product) {
        return new AdminProductQueryInfo(product.productId(), product.name(), product.price(), product.stockQuantity(),
            new BrandInfo(product.brandId(), product.brandName()), product.likeCount(),
            product.createdAt(), product.updatedAt(), product.deletedAt());
    }
}
