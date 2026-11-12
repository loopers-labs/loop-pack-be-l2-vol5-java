package com.loopers.domain.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderOutboxRepository {
    List<OrderOutbox> findDue(Instant now, int limit);

    Optional<OrderOutbox> findById(Long id);

    Optional<OrderOutbox> findByEventId(String eventId);
}
