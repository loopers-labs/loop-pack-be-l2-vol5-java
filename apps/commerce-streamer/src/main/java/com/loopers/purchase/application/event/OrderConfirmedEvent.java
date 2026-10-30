package com.loopers.purchase.application.event;

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
    public record Item(Long productId, int quantity) {
    }
}
