package com.loopers.interfaces.consumer;

import com.loopers.domain.activity.PurchaseLine;

import java.time.Instant;
import java.util.List;

record OrderConfirmedPayload(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {

    record Item(Long productId, Integer quantity) {
    }

    boolean hasEmptyItem() {
        return items != null && items.stream().anyMatch(item -> item == null || item.quantity() == null);
    }

    List<PurchaseLine> lines() {
        if (items == null) {
            return null;
        }
        return items.stream()
            .map(item -> new PurchaseLine(item.productId(), item.quantity()))
            .toList();
    }
}
