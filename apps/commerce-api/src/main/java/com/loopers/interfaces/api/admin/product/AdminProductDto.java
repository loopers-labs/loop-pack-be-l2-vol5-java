package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.AdminProductInfo;

import java.time.ZonedDateTime;

public class AdminProductDto {
    public record CreateRequest(Long brandId, String name, Long price, Long stock) {}

    // 브랜드·재고는 받지 않는다(7-3, T-8)
    public record UpdateRequest(String name, Long price) {}

    public record StockRequest(Long stock) {}

    public record ProductResponse(
        Long productId,
        Long brandId,
        String name,
        Long price,
        Long stock,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt
    ) {
        public static ProductResponse from(AdminProductInfo info) {
            return new ProductResponse(info.id(), info.brandId(), info.name(), info.price(), info.stock(), info.createdAt(), info.updatedAt());
        }
    }
}
