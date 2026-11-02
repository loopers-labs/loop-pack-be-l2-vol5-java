package com.loopers.infrastructure.purchase.persistence;

import com.loopers.domain.purchase.entity.ProductPurchaseActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivity, Long> {

    List<ProductPurchaseActivity> findAllByOrderId(Long orderId);
}
