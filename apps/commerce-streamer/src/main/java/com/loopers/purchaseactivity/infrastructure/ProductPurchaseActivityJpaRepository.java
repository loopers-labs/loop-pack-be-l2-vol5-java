package com.loopers.purchaseactivity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivityEntity, Long> {
    List<ProductPurchaseActivityEntity> findByOrderIdOrderByProductId(long orderId);
}
