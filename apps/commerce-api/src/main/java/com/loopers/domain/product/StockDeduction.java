package com.loopers.domain.product;

import com.loopers.domain.common.Quantity;

public interface StockDeduction {

    void deductStock(Long productId, Quantity amount);
}
