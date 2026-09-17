package com.loopers.application.product;

import com.loopers.domain.product.Product;

public record ProductResult(long id, long brandId, String name, long price, int stock, boolean deleted) {
    public static ProductResult from(Product product) {
        return new ProductResult(product.getId().value(), product.getBrandId().value(), product.getName(),
            product.getPrice().value(), product.getStock().value(), product.isDeleted());
    }
}
