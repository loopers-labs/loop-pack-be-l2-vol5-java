package com.loopers.infrastructure.product;

import com.loopers.domain.product.StockHistoryModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockHistoryJpaRepository extends JpaRepository<StockHistoryModel, Long> {
}
