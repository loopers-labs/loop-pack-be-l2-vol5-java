package com.loopers.domain.product;

import com.loopers.domain.common.Quantity;

public interface StockRestoration {

    void restoreStock(Long productId, Quantity amount);
}
