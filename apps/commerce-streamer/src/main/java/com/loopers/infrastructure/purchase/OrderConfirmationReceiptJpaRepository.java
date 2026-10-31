package com.loopers.infrastructure.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface OrderConfirmationReceiptJpaRepository extends JpaRepository<OrderConfirmationReceiptEntity, Long> {

    Optional<OrderConfirmationReceiptEntity> findByEventId(String eventId);

    Optional<OrderConfirmationReceiptEntity> findByOrderId(Long orderId);
}
