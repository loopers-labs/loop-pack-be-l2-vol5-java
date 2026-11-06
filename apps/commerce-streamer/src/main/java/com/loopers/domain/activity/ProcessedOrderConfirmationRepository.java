package com.loopers.domain.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedOrderConfirmationRepository extends JpaRepository<ProcessedOrderConfirmation, Long> {
    Optional<ProcessedOrderConfirmation> findByEventId(String eventId);
    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);
}
