package com.loopers.application.product;

import com.loopers.domain.product.ProductWithBrand;

import java.time.ZonedDateTime;

public record AdminProductInfo(
    Long id,
    String name,
    long price,
    int stock,
    Long brandId,
    String brandName,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {
    public static AdminProductInfo from(ProductWithBrand product) {
        return new AdminProductInfo(
            product.id(),
            product.name(),
            product.price(),
            product.stock(),
            product.brandId(),
            product.brandName(),
            product.createdAt(),
            product.updatedAt()
        );
    }
}
