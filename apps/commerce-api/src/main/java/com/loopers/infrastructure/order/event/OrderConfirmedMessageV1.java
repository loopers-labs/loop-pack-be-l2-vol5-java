package com.loopers.infrastructure.order.event;

import com.loopers.application.order.event.OrderConfirmedEvent;

import java.util.List;

record OrderConfirmedMessageV1(
        String eventType,
        int schemaVersion,
        String eventId,
        Long orderId,
        String confirmedAt,
        List<Item> items
) {
    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int    SCHEMA_VERSION = 1;

    static OrderConfirmedMessageV1 from(OrderConfirmedEvent event) {
        List<Item> items = event.getItems().stream()
                                .map(item -> new Item(item.productId(), item.quantity()))
                                .toList();
        return new OrderConfirmedMessageV1(
                EVENT_TYPE, SCHEMA_VERSION, event.getEventId(), event.getOrderId(),
                event.getConfirmedAt().toInstant().toString(), items
        );
    }

    String key() {
        return String.valueOf(orderId);
    }

    record Item(
            Long productId,
            int quantity
    ) {}
}
