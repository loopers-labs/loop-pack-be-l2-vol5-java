package com.loopers.infrastructure.purchase;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedOrderConfirmationJpaRepository extends JpaRepository<ProcessedOrderConfirmationJpaEntity, Long> {

    Optional<ProcessedOrderConfirmationJpaEntity> findByEventId(String eventId);

    Optional<ProcessedOrderConfirmationJpaEntity> findByOrderId(long orderId);
}
