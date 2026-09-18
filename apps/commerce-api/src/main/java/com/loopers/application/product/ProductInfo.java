package com.loopers.application.product;

import com.loopers.domain.product.Product;

public record ProductInfo(
    Long productId,
    String productName,
    long price,
    int stockQuantity,
    Long brandId,
    String brandName,
    long likeCount
) {
    public static ProductInfo of(Product product, String brandName, long likeCount) {
        return new ProductInfo(
            product.getId(),
            product.getName(),
            product.getPrice().getAmount(),
            product.getStock().getQuantity(),
            product.getBrandId(),
            brandName,
            likeCount
        );
    }
}
