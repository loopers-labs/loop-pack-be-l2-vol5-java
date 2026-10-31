package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedOrderConfirmationJpaRepository extends JpaRepository<ProcessedOrderConfirmation, Long> {
    Optional<ProcessedOrderConfirmation> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);
}
