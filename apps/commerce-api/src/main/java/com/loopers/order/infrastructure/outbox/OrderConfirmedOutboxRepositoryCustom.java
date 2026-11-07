package com.loopers.order.infrastructure.outbox;

import java.time.Instant;
import java.util.List;

interface OrderConfirmedOutboxRepositoryCustom {

    List<OrderConfirmedOutbox> findReadyForRelay(Instant now, int limit);
}
