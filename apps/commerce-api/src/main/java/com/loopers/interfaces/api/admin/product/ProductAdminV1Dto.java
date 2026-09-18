package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductAdminInfo;

import java.time.ZonedDateTime;

public class ProductAdminV1Dto {
    public record ProductCreateRequest(Long brandId, String name, Long price, int stock) {}

    public record ProductUpdateRequest(String name, Long price) {}

    public record StockRequest(int stock) {}

    public record ProductResponse(Long id, Long brandId, String name, Long price, int stock, ZonedDateTime deletedAt) {
        public static ProductResponse from(ProductAdminInfo info) {
            return new ProductResponse(info.id(), info.brandId(), info.name(), info.price(), info.stock(), info.deletedAt());
        }
    }
}
