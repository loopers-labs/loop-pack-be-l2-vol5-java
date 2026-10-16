package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.CustomerProductInfo;
import com.loopers.application.product.ProductPage;
import com.loopers.application.product.ProductListItemInfo;

import java.util.List;

public class ProductV1Dto {

    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name());
        }
    }

    public record ProductResponse(Long id, BrandResponse brand, String name, long price, long likeCount) {
        public static ProductResponse from(CustomerProductInfo info) {
            return new ProductResponse(
                info.id(),
                BrandResponse.from(info.brand()),
                info.name(),
                info.price(),
                info.likeCount()
            );
        }
    }

    public record ProductListItemResponse(Long id, BrandResponse brand, String name, long price,
                                         long likeCount, boolean isLiked) {
        public static ProductListItemResponse from(ProductListItemInfo item) {
            var product = item.product();
            return new ProductListItemResponse(product.id(), BrandResponse.from(product.brand()), product.name(),
                product.price(), product.likeCount(), item.isLiked());
        }
    }

    public record ProductListResponse(List<ProductListItemResponse> items, int page, int size) {
        public static ProductListResponse from(ProductPage<ProductListItemInfo> page) {
            return new ProductListResponse(
                page.items().stream().map(ProductListItemResponse::from).toList(),
                page.page(),
                page.size()
            );
        }
    }
}
