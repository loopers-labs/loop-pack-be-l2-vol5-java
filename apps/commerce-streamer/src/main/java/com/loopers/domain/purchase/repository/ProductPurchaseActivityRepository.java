package com.loopers.domain.purchase.repository;

import com.loopers.domain.purchase.entity.ProductPurchaseActivity;

import java.util.List;

public interface ProductPurchaseActivityRepository {

    ProductPurchaseActivity save(ProductPurchaseActivity activity);

    List<ProductPurchaseActivity> findAllByOrderId(Long orderId);
}
