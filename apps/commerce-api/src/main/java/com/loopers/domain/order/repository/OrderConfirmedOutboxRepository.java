package com.loopers.domain.order.repository;

import com.loopers.domain.order.entity.OrderConfirmedOutbox;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxRepository {
    OrderConfirmedOutbox save(OrderConfirmedOutbox outbox);

    Optional<OrderConfirmedOutbox> findById(Long id);

    Optional<OrderConfirmedOutbox> findByOrderId(Long orderId);

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    /** PENDING 이고 다음 시도 시각이 지난 행을 id 순으로 (7주차 D-104) */
    List<OrderConfirmedOutbox> findDue(ZonedDateTime now, int limit);
}
