package com.loopers.domain.product;

import com.loopers.domain.brand.BrandName;

import java.time.ZonedDateTime;

public record ProductSummary(Long productId, String name, long price, int stockQuantity,
                             Long brandId, String brandName, long likeCount,
                             ZonedDateTime createdAt, ZonedDateTime updatedAt, ZonedDateTime deletedAt) {

    public ProductSummary(Long productId, String name, long price, int stockQuantity,
                          Long brandId, BrandName brandName, long likeCount,
                          ZonedDateTime createdAt, ZonedDateTime updatedAt, ZonedDateTime deletedAt) {
        this(productId, name, price, stockQuantity, brandId, brandName.value(), likeCount,
            createdAt, updatedAt, deletedAt);
    }
}
