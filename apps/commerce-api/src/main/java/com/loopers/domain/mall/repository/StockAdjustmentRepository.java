package com.loopers.domain.mall.repository;

import com.loopers.domain.mall.model.StockAdjustment;
import java.util.Optional;

public interface StockAdjustmentRepository {
    StockAdjustment insert(StockAdjustment adjustment);
    StockAdjustment save(StockAdjustment adjustment);
    Optional<StockAdjustment> findByOrderId(long orderId);
}
