package com.loopers.order.application.event;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;

import java.time.Instant;
import java.util.List;

public record OrderConfirmedEvent(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    Instant confirmedAt,
    List<Item> items
) {

    public OrderConfirmedEvent {
        items = List.copyOf(items);
    }

    public static OrderConfirmedEvent from(Order order) {
        List<Item> items = order.getItems().stream()
            .map(OrderConfirmedEvent::toItem)
            .toList();
        return new OrderConfirmedEvent(
            "OrderConfirmed",
            1,
            "order-confirmed:" + order.getId(),
            order.getId(),
            order.getConfirmedAt().toInstant(),
            items
        );
    }

    private static Item toItem(OrderItem item) {
        return new Item(item.productId(), item.quantity());
    }

    public record Item(Long productId, int quantity) {
    }
}
