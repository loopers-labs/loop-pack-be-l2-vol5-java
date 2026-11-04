package com.loopers.domain.order;

import java.util.Optional;

public interface OrderOutboxRepository {
    OrderOutboxModel save(OrderOutboxModel outbox);

    Optional<OrderOutboxModel> findByEventId(String eventId);
}
