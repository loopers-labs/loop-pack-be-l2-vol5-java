package com.loopers.domain.purchase.repository;

import com.loopers.domain.purchase.entity.ProcessedOrderConfirmation;

import java.util.Optional;

public interface ProcessedOrderConfirmationRepository {

    ProcessedOrderConfirmation save(ProcessedOrderConfirmation processed);

    Optional<ProcessedOrderConfirmation> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);
}
