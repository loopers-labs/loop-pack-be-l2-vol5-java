package com.loopers.purchaseactivity.domain;

import java.util.List;

public interface ProcessedOrderConfirmationRepository {
    List<ProcessedOrderConfirmation> findByEventIdOrOrderId(String eventId, long orderId);

    void save(ProcessedOrderConfirmation confirmation);
}
