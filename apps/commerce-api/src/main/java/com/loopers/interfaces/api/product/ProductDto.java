package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.application.product.ProductInfo;

public class ProductDto {
    public record ProductResponse(Long productId, String name, Long price, BrandSummary brand, Long likeCount) {
        public static ProductResponse from(ProductInfo info) {
            return new ProductResponse(info.id(), info.name(), info.price(), BrandSummary.from(info.brand()), info.likeCount());
        }
    }

    public record BrandSummary(Long brandId, String name) {
        public static BrandSummary from(BrandInfo info) {
            return new BrandSummary(info.id(), info.name());
        }
    }
}
