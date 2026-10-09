package com.loopers.product.domain;

import java.util.Optional;

public interface StockRepository {
    Stock save(Stock stock);
    Optional<Stock> findByProductId(Long productId);
    Optional<Stock> findForOrderByProductId(Long productId);

    Optional<Stock> findForStockUpdateByProductId(Long productId);
}
