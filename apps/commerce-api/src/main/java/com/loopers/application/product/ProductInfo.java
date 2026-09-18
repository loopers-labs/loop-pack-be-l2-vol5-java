package com.loopers.application.product;

import com.loopers.domain.product.Product;

public record ProductInfo(long productId, long brandId, String name, long price, int stock, boolean deleted) {
    public static ProductInfo from(Product p) {
        return new ProductInfo(p.getId(), p.getBrandId(), p.getName(), p.getPrice(), p.getStock(), p.isDeleted());
    }
}
