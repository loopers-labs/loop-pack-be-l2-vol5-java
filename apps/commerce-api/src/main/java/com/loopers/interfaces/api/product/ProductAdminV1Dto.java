package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class ProductAdminV1Dto {
    public record CreateRequest(String name, Long price, Long brandId, Integer initialStock) {}

    public record UpdateRequest(String name, Long price) {}

    public record StockRequest(Integer quantity) {}

    public record ProductResponse(
        Long id,
        String name,
        Long price,
        Long brandId,
        int remainingStock,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt,
        ZonedDateTime deletedAt
    ) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(),
                info.name(),
                info.price(),
                info.brandId(),
                info.remainingStock(),
                info.createdAt(),
                info.updatedAt(),
                info.deletedAt()
            );
        }
    }

    public record ProductListResponse(List<ProductResponse> products) {
        public static ProductListResponse from(List<ProductInfo> infos) {
            return new ProductListResponse(infos.stream().map(ProductResponse::from).toList());
        }
    }
}
