package com.loopers.purchase.adapter.out.persistence;

import com.loopers.purchase.domain.ProductPurchaseActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPurchaseActivityJpaRepository extends JpaRepository<ProductPurchaseActivity, Long> {

    List<ProductPurchaseActivity> findByOrderIdOrderByProductId(Long orderId);
}
