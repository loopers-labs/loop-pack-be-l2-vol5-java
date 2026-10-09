package com.loopers.product.infrastructure;

import com.loopers.product.domain.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

interface StockJpaRepository extends JpaRepository<Stock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Stock> findForOrderByProductId(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Stock> findForStockUpdateByProductId(Long productId);

    Optional<Stock> findByProductId(Long productId);
}
