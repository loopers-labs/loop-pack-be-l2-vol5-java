package com.loopers.domain.purchase;

import java.util.Optional;

public interface PurchaseActivityRepository {

    Optional<ProcessedOrderConfirmation> findProcessedByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findProcessedByOrderId(Long orderId);

    /** 저장하고 바로 DB로 내보낸다. 유일 제약 위반이 이 호출에서 드러나게 한다. */
    ProcessedOrderConfirmation saveProcessed(ProcessedOrderConfirmation processed);

    ProductPurchaseActivity saveActivity(ProductPurchaseActivity activity);
}
