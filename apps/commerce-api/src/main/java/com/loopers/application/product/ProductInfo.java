package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

public record ProductInfo(Long id, Long brandId, String brandName, String name, Long price, long likeCount) {
    public static ProductInfo from(ProductModel model, String brandName, long likeCount) {
        return new ProductInfo(
            model.getId(),
            model.getBrandId(),
            brandName,
            model.getName(),
            model.getPrice(),
            likeCount
        );
    }
}
