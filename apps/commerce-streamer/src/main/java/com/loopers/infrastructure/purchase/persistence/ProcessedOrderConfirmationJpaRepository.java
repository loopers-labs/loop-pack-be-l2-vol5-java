package com.loopers.infrastructure.purchase.persistence;

import com.loopers.domain.purchase.entity.ProcessedOrderConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessedOrderConfirmationJpaRepository extends JpaRepository<ProcessedOrderConfirmation, Long> {

    Optional<ProcessedOrderConfirmation> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmation> findByOrderId(Long orderId);
}
