package com.loopers.purchase.domain;

import java.util.List;

/**
 * 받은 OrderConfirmed (W6 D-6). Red 단계의 뼈대.
 */
public record OrderConfirmedMessage(
    String eventId,
    String eventType,
    int schemaVersion,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    public record Item(Long productId, int quantity) {}

    public List<Item> normalizedItems() {
        return items;
    }

    public String fingerprint() {
        return "";
    }
}
