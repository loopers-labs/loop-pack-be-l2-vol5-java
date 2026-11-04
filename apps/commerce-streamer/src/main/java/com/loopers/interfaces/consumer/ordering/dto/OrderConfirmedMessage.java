package com.loopers.interfaces.consumer.ordering.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderConfirmedMessage(UUID eventId, long orderId, Instant confirmedAt, List<Item> items) {
    public OrderConfirmedMessage {
        items = List.copyOf(items);
    }

    public record Item(long productId, int quantity) {}
}
