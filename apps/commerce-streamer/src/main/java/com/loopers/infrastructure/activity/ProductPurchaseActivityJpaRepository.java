package com.loopers.infrastructure.activity;

import com.loopers.domain.activity.ProductPurchaseActivityModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivityModel, Long> {

    List<ProductPurchaseActivityModel> findByOrderIdOrderByProductIdAsc(Long orderId);
}
