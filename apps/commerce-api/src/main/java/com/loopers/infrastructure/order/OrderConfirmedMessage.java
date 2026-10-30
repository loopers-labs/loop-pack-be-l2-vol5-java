package com.loopers.infrastructure.order;

import com.loopers.application.order.dto.OrderConfirmedInfo;

import java.time.Instant;
import java.util.List;

/** {@code order-events} topic 에 실리는 OrderConfirmed(schemaVersion 1) 계약. 시각은 UTC 로 보낸다. */
record OrderConfirmedMessage(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    Instant confirmedAt,
    List<Item> items
) {
    static final String EVENT_TYPE = "OrderConfirmed";
    static final int SCHEMA_VERSION = 1;

    record Item(Long productId, int quantity) {}

    static OrderConfirmedMessage from(OrderConfirmedInfo event) {
        return new OrderConfirmedMessage(
            EVENT_TYPE,
            SCHEMA_VERSION,
            event.eventId(),
            event.orderId(),
            event.confirmedAt().toInstant(),
            event.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList()
        );
    }
}
