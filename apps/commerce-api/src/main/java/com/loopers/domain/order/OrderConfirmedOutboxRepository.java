package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxRepository {

    OrderConfirmedOutbox save(OrderConfirmedOutbox outbox);

    Optional<OrderConfirmedOutbox> findById(Long id);

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);

    List<OrderConfirmedOutbox> findPendingToProcess(ZonedDateTime before, int limit);
}
