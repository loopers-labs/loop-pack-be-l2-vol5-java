package com.loopers.interfaces.consumer;

import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.PurchaseLine;
import com.loopers.domain.activity.PurchaseReading;

import java.time.Instant;
import java.util.List;

record OrderConfirmedPayload(String eventId, Long orderId, Instant confirmedAt, List<Item> items) {

    record Item(Long productId, Integer quantity) {
    }

    boolean hasEmptyItem() {
        return items != null && items.stream().anyMatch(item -> item == null || item.quantity() == null);
    }

    PurchaseReading toReading(String key) {
        return ConfirmedPurchase.read(key, eventId, orderId, confirmedAt, lines());
    }

    private List<PurchaseLine> lines() {
        if (items == null) {
            return null;
        }
        return items.stream()
            .map(item -> new PurchaseLine(item.productId(), item.quantity()))
            .toList();
    }
}
