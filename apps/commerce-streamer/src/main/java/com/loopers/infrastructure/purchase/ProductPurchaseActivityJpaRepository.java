package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivity, Long> {
    List<ProductPurchaseActivity> findByOrderId(Long orderId);
}
