package com.loopers.infrastructure.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivityJpaEntity, Long> {
    List<ProductPurchaseActivityJpaEntity> findAllByOrderIdOrderByProductIdAsc(long orderId);
}
