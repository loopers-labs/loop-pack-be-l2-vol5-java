package com.loopers.purchaseactivity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface ProcessedOrderConfirmationJpaRepository extends JpaRepository<ProcessedOrderConfirmationEntity, Long> {
    Optional<ProcessedOrderConfirmationEntity> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmationEntity> findByOrderId(long orderId);

    List<ProcessedOrderConfirmationEntity> findByEventIdOrOrderId(String eventId, long orderId);
}
