package com.loopers.purchase.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityRepository
    extends JpaRepository<ProductPurchaseActivity, ProductPurchaseActivityId> {
    List<ProductPurchaseActivity> findAllById_OrderIdOrderById_ProductId(Long orderId);
}
