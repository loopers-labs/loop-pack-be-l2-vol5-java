package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductInfo;

public class ProductV1Dto {
    public record ProductResponse(
        Long id, Long brandId, String brandName, String name, Long price, int stock, long likeCount
    ) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(), info.brandId(), info.brandName(), info.name(), info.price(), info.stock(), info.likeCount()
            );
        }
    }
}
