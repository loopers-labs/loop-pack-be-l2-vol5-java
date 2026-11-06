package com.loopers.application.activity;

import java.time.ZonedDateTime;
import java.util.List;

public record OrderConfirmedEventDto(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    ZonedDateTime confirmedAt,
    List<Item> items
) {
    public record Item(Long productId, int quantity) {}

    public String computeFingerprint() {
        List<String> sortedItems = items.stream()
            .sorted((a, b) -> Long.compare(a.productId(), b.productId()))
            .map(i -> i.productId() + ":" + i.quantity())
            .toList();
        return eventId + "|" + orderId + "|" + confirmedAt + "|" + sortedItems;
    }
}
