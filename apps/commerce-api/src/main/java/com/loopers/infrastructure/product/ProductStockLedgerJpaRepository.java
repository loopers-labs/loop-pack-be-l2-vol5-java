package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductStockLedgerModel;
import org.springframework.data.jpa.repository.JpaRepository;

/** append-only (DR-38). 저장만 한다. */
public interface ProductStockLedgerJpaRepository extends JpaRepository<ProductStockLedgerModel, Long> {
}
