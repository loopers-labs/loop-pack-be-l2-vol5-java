package com.loopers.domain.purchase;

import java.util.List;

public interface ProductPurchaseActivityRepository {

    List<ProductPurchaseActivity> saveAllAndFlush(List<ProductPurchaseActivity> activities);

    List<ProductPurchaseActivity> findByOrderId(Long orderId);
}
