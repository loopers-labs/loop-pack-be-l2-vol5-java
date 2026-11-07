package com.loopers.order.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderConfirmedOutboxRepository extends JpaRepository<OrderConfirmedOutbox, Long>,
    OrderConfirmedOutboxRepositoryCustom {

    List<OrderConfirmedOutbox> findReadyForRelay(Instant now, int limit);

    Optional<OrderConfirmedOutbox> findByEventId(String eventId);
}
