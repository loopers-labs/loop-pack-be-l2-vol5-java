package com.loopers.domain.purchase;

import java.util.List;

public interface ProductPurchaseActivityRepository {

    /** 바로 insert한다. (order_id, product_id) unique에 걸리면 이 호출에서 예외가 난다(INV-605). */
    ProductPurchaseActivity insert(ProductPurchaseActivity activity);

    List<ProductPurchaseActivity> findAllByOrderId(Long orderId);
}
