package com.loopers.domain.purchase;

import java.time.Instant;
import java.util.Optional;

public interface PurchaseActivityStore {
    void insertConfirmation(OrderConfirmation confirmation);

    void insertActivity(long orderId, Instant confirmedAt, OrderConfirmation.Item item);

    Optional<OrderConfirmation> findByEventId(String eventId);

    Optional<OrderConfirmation> findByOrderId(long orderId);
}
