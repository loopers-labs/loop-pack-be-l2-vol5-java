package com.loopers.application.product;

import com.loopers.domain.product.ProductView;

public record ProductInfo(
    Long id,
    String name,
    long price,
    int stock,
    long likeCount,
    Long brandId,
    String brandName,
    String brandDescription
) {
    public static ProductInfo from(ProductView view) {
        return new ProductInfo(
            view.id(),
            view.name(),
            view.price(),
            view.stock(),
            view.likeCount(),
            view.brandId(),
            view.brandName(),
            view.brandDescription()
        );
    }
}
