package com.loopers.application.product;

import com.loopers.domain.product.Product;

public record CustomerProductInfo(
    Long id,
    Long brandId,
    String brandName,
    String name,
    long price,
    long likeCount
) {
    public static CustomerProductInfo from(Product product, long likeCount) {
        return new CustomerProductInfo(
            product.getId(),
            product.getBrand().getId(),
            product.getBrand().getName(),
            product.getName(),
            product.getPrice(),
            likeCount
        );
    }
}
