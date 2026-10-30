package com.loopers.domain.purchase;

import java.util.List;

public interface PurchaseActivityRepository {
    List<ProcessedOrderConfirmation> findProcessed(String eventId, long orderId);
    List<ProductPurchaseActivity> findActivities(long orderId);
    void saveCompletion(ProcessedOrderConfirmation confirmation);
    void saveActivity(ProductPurchaseActivity activity);
}
