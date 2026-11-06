package com.loopers.domain.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityRepository extends JpaRepository<ProductPurchaseActivity, Long> {
    List<ProductPurchaseActivity> findAllByOrderId(Long orderId);
}
