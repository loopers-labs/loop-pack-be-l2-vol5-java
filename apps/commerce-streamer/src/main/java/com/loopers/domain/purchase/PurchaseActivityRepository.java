package com.loopers.domain.purchase;

import java.util.List;
import java.util.Optional;

/**
 * 장부(processed_order_confirmation)와 구매 활동(product_purchase_activity)은 한 TX 에서 함께 쓰고
 * 중복 판단 때 함께 읽으므로 리포지터리 하나로 둔다.
 */
public interface PurchaseActivityRepository {

    Optional<ProcessedOrderConfirmation> findProcessedByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findProcessedByOrderId(Long orderId);

    ProcessedOrderConfirmation saveProcessed(ProcessedOrderConfirmation confirmation);

    ProductPurchaseActivity saveActivity(ProductPurchaseActivity activity);

    List<ProductPurchaseActivity> findActivitiesByOrderId(Long orderId);
}
