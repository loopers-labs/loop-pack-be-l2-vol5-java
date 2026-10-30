package com.loopers.infrastructure.purchase;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessedOrderConfirmationJpaRepository extends JpaRepository<ProcessedOrderConfirmation, Long> {
    List<ProcessedOrderConfirmation> findByEventIdOrOrderId(String eventId, Long orderId);
}
