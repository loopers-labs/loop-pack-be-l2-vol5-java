package com.loopers.product.domain;

import java.util.Optional;
import java.util.List;

public interface StockRepository {
    Stock save(Stock stock);
    List<Stock> findAllByProductIds(List<Long> productIds);
    Optional<Stock> findByProductId(Long productId);
    Optional<Stock> findForOrderByProductId(Long productId);

    Optional<Stock> findForStockUpdateByProductId(Long productId);
}
