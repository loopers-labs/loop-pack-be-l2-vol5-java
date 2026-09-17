package com.loopers.application.product;

import com.loopers.domain.product.Product;

public record ProductInfo(
    Long id,
    Long brandId,
    String name,
    long price,
    long stock,
    boolean deleted
) {
    public static ProductInfo from(Product product) {
        return new ProductInfo(
            product.getId(),
            product.getBrand().getId(),
            product.getName(),
            product.getPrice(),
            product.getStock().amount(),
            product.getDeletedAt() != null
        );
    }
}
