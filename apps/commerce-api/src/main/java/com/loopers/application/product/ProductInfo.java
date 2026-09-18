package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.product.ProductSummary;

public record ProductInfo(Long productId, String name, long price, int stockQuantity,
                          BrandInfo brand, long likeCount) {

    public static ProductInfo from(ProductSummary product) {
        return new ProductInfo(product.productId(), product.name(), product.price(), product.stockQuantity(),
            new BrandInfo(product.brandId(), product.brandName()), product.likeCount());
    }
}
