package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductInfo;

public class ProductV1Dto {

    public record CreateRequest(Long brandId, String name, long price) {}

    public record StockUpdateRequest(long quantity) {}

    public record ProductResponse(
        Long id,
        Long brandId,
        String name,
        long price,
        long stock,
        boolean deleted
    ) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(),
                info.brandId(),
                info.name(),
                info.price(),
                info.stock(),
                info.deleted()
            );
        }
    }

    public record StockResponse(Long productId, long stock) {
        public static StockResponse from(ProductInfo info) {
            return new StockResponse(info.id(), info.stock());
        }
    }
}
