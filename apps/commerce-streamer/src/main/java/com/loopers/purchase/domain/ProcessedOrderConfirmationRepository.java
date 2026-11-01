package com.loopers.purchase.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedOrderConfirmationRepository extends JpaRepository<ProcessedOrderConfirmation, String> {
    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);
}
