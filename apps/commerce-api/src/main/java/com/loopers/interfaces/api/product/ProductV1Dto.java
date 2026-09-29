package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductInfo;

public class ProductV1Dto {
    public record ProductResponse(
        Long productId,
        String productName,
        long price,
        int stockQuantity,
        Long brandId,
        String brandName,
        long likeCount
    ) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.productId(),
                info.productName(),
                info.price(),
                info.stockQuantity(),
                info.brandId(),
                info.brandName(),
                info.likeCount()
            );
        }
    }
}
