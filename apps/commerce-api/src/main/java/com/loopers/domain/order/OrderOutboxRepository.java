package com.loopers.domain.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderOutboxRepository {
    OrderOutboxModel save(OrderOutboxModel outbox);

    Optional<OrderOutboxModel> findByEventId(String eventId);

    // 재시도 시각이 된 PENDING을 (다음 시도 시각, id) 순으로 최대 limit개. idx_order_outbox_relay를 쓴다
    List<String> findDuePendingEventIds(Instant now, int limit);
}
