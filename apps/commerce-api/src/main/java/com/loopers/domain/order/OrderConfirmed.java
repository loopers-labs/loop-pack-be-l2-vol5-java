package com.loopers.domain.order;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class OrderConfirmed implements OrderEvent {

    private static final String EVENT_ID_PREFIX = "order-confirmed:v1:";

    private final String eventId;
    private final Long orderId;
    private final Instant confirmedAt;
    private final List<Item> items;

    public record Item(Long productId, int quantity) {
    }

    private OrderConfirmed(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt;
        this.items = List.copyOf(items);
    }

    static OrderConfirmed of(Order order) {
        List<Item> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::productId))
            .map(item -> new Item(item.productId(), item.quantity().value()))
            .toList();
        return new OrderConfirmed(EVENT_ID_PREFIX + order.getId(), order.getId(), order.getConfirmedAt(), items);
    }

    public String eventId() {
        return eventId;
    }

    public Long orderId() {
        return orderId;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public List<Item> items() {
        return items;
    }

    @Override
    public String toString() {
        return "OrderConfirmed[eventId=" + eventId + ", confirmedAt=" + confirmedAt + ", items=" + items + "]";
    }
}
