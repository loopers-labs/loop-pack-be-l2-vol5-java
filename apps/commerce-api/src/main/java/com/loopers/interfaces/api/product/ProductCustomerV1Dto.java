package com.loopers.interfaces.api.product;

import com.loopers.application.product.CustomerProductInfo;

public class ProductCustomerV1Dto {

    public record ProductResponse(
        Long id,
        Long brandId,
        String brandName,
        String name,
        long price,
        long likeCount
    ) {
        public static ProductResponse from(CustomerProductInfo info) {
            return new ProductResponse(
                info.id(),
                info.brandId(),
                info.brandName(),
                info.name(),
                info.price(),
                info.likeCount()
            );
        }
    }
}
