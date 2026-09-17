package com.loopers.interfaces.api.product;

import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductListInfo;

import java.util.List;

public class ProductV1Dto {
    public record ProductResponse(Long id, Long brandId, String brandName, String name, Long price, long likeCount) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(
                info.id(), info.brandId(), info.brandName(), info.name(), info.price(), info.likeCount()
            );
        }
    }

    public record ProductsResponse(List<ProductResponse> items, int page, int size, long totalCount) {
        public static ProductsResponse from(ProductListInfo info) {
            return new ProductsResponse(
                info.items().stream().map(ProductResponse::from).toList(),
                info.page(), info.size(), info.totalCount()
            );
        }
    }
}
