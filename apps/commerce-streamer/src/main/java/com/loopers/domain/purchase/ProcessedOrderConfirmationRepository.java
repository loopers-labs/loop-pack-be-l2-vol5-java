package com.loopers.domain.purchase;

import java.util.Optional;

public interface ProcessedOrderConfirmationRepository {
    Optional<ProcessedOrderConfirmation> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);

    void save(ProcessedOrderConfirmation confirmation);
}
