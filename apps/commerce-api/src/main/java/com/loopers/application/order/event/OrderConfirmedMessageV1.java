package com.loopers.application.order.event;

import java.util.List;

public record OrderConfirmedMessageV1(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {
    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;

    public static OrderConfirmedMessageV1 from(OrderConfirmedEvent event) {
        List<Item> items = event.getItems().stream()
            .map(item -> new Item(item.productId(), item.quantity()))
            .toList();
        return new OrderConfirmedMessageV1(
            EVENT_TYPE,
            SCHEMA_VERSION,
            event.getEventId(),
            event.getOrderId(),
            event.getConfirmedAt().toInstant().toString(),
            items
        );
    }

    public String key() {
        return String.valueOf(orderId);
    }

    public record Item(Long productId, int quantity) {}
}
