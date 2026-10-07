package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductAdminInfo;
import com.loopers.application.product.ProductListAdminInfo;

import java.util.List;

public class ProductAdminV1Dto {
    public record CreateRequest(Long brandId, String name, Long price, int stock) {
    }

    public record UpdateRequest(String name, Long price) {
    }

    public record StockRequest(int quantity) {
    }

    public record ProductResponse(Long id, Long brandId, String name, Long price, int stock, boolean deleted) {
        public static ProductResponse from(ProductAdminInfo info) {
            return new ProductResponse(
                info.id(), info.brandId(), info.name(), info.price(), info.stock(), info.deleted()
            );
        }
    }

    public record ProductsResponse(List<ProductResponse> items, int page, int size, long totalCount) {
        public static ProductsResponse from(ProductListAdminInfo info) {
            return new ProductsResponse(
                info.items().stream().map(ProductResponse::from).toList(),
                info.page(), info.size(), info.totalCount()
            );
        }
    }
}
