package com.loopers.application.product;

import com.loopers.domain.product.ProductStockModel;

/** 설계 4-3-0 StockResponse. */
public record StockInfo(Long productId, Integer stock) {
    public static StockInfo from(ProductStockModel stock) {
        return new StockInfo(stock.getProductId(), stock.getAvailable());
    }
}
