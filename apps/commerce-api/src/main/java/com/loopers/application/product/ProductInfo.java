package com.loopers.application.product;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.product.Product;

public record ProductInfo(Long id, String name, Long price, BrandInfo brand, long likeCount) {
    public static ProductInfo of(Product product, BrandInfo brand, long likeCount) {
        return new ProductInfo(product.getId(), product.getName(), product.getPrice(), brand, likeCount);
    }
}
