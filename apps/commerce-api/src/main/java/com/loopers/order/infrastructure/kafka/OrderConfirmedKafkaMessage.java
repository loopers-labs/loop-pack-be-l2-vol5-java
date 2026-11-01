package com.loopers.order.infrastructure.kafka;

import com.loopers.order.application.event.OrderConfirmedEvent;

import java.util.List;

public record OrderConfirmedKafkaMessage(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    public OrderConfirmedKafkaMessage {
        items = List.copyOf(items);
    }

    public static OrderConfirmedKafkaMessage from(OrderConfirmedEvent event) {
        List<Item> items = event.items().stream()
            .map(item -> new Item(item.productId(), item.quantity()))
            .toList();
        return new OrderConfirmedKafkaMessage(
            event.eventType(),
            event.schemaVersion(),
            event.eventId(),
            event.orderId(),
            event.confirmedAt().toString(),
            items
        );
    }

    public record Item(Long productId, int quantity) {
    }
}
