package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivity, Long> {
}
