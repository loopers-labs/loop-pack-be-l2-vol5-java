package com.loopers.infrastructure.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessedOrderConfirmationJpaRepository
    extends JpaRepository<ProcessedOrderConfirmationJpaEntity, Long> {
    List<ProcessedOrderConfirmationJpaEntity> findAllByEventIdOrOrderId(String eventId, long orderId);
}
