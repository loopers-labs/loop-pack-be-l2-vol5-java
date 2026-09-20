package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;

/** 설계 4-3-0 StockResponse. */
public record StockInfo(Long productId, Integer stock) {
    public static StockInfo from(ProductModel product) {
        return new StockInfo(product.getId(), product.getStock());
    }
}
