package com.loopers.infrastructure.purchaseactivity;

import com.loopers.domain.purchaseactivity.ProductPurchaseActivityModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivityModel, Long> {

    List<ProductPurchaseActivityModel> findByOrderIdOrderByProductIdAsc(Long orderId);
}
