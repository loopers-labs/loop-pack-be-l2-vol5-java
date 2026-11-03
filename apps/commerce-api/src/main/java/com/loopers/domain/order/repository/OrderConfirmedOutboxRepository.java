package com.loopers.domain.order.repository;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;

import java.util.Optional;

public interface OrderConfirmedOutboxRepository {
    OrderConfirmedOutbox save(OrderConfirmedOutbox outbox);

    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);
}
