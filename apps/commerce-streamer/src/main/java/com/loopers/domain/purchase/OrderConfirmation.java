package com.loopers.domain.purchase;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An immutable incoming fact, independent of the producer application's classes and current product state. */
public record OrderConfirmation(String eventType, int schemaVersion, String eventId, long orderId,
                                Instant confirmedAt, List<Item> items) {
    public OrderConfirmation {
        if (!"OrderConfirmed".equals(eventType) || schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported order confirmation event contract");
        }
        if (eventId == null || eventId.isBlank() || orderId <= 0 || confirmedAt == null) {
            throw new IllegalArgumentException("Order confirmation requires identities and a confirmation instant");
        }
        items = normalize(items);
    }

    private static List<Item> normalize(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Order confirmation requires purchased items");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("Purchased item must not be null");
            }
            try {
                quantities.merge(item.productId(), item.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Combined purchase quantity exceeds its supported range");
            }
        }
        return quantities.entrySet().stream().map(entry -> new Item(entry.getKey(), entry.getValue())).toList();
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("Purchased item requires a positive product identity and quantity");
            }
        }
    }
}
