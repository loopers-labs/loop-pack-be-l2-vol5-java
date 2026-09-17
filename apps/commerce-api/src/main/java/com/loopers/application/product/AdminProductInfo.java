package com.loopers.application.product;

import com.loopers.domain.product.Product;

import java.time.ZonedDateTime;

public record AdminProductInfo(
    Long id,
    Long brandId,
    String name,
    Long price,
    Long stock,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {
    public static AdminProductInfo from(Product product) {
        return new AdminProductInfo(
            product.getId(),
            product.getBrandId(),
            product.getName(),
            product.getPrice(),
            product.getStock(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }
}
