package com.loopers.kafka.event;

import java.time.Instant;
import java.util.List;
import java.util.TreeMap;

public record OrderConfirmed(
        String eventType,
        int schemaVersion,
        String eventId,
        long orderId,
        Instant confirmedAt,
        List<Item> items) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public OrderConfirmed {
        if (!EVENT_TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported order confirmation contract");
        }
        if (eventId == null || eventId.isBlank() || orderId <= 0 || confirmedAt == null) {
            throw new IllegalArgumentException("A confirmation needs eventId, orderId and confirmedAt");
        }
        if (confirmedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("confirmedAt must preserve MySQL microsecond precision");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A confirmation needs at least one purchased item");
        }
        var quantities = new TreeMap<Long, Integer>();
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("A purchased item must not be null");
            }
            try {
                quantities.merge(item.productId(), item.quantity(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Purchased quantity exceeds the supported range", overflow);
            }
        }
        items = quantities.entrySet().stream()
                .map(entry -> new Item(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static OrderConfirmed confirmed(long orderId, Instant confirmedAt, List<Item> items) {
        return new OrderConfirmed(EVENT_TYPE, SCHEMA_VERSION,
                "order-confirmed:v1:" + orderId, orderId, confirmedAt, items);
    }

    public void validateKey(String key) {
        if (!Long.toString(orderId).equals(key)) {
            throw new IllegalArgumentException("Kafka key must match orderId");
        }
    }

    public record Item(long productId, int quantity) {
        public Item {
            if (productId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("productId and quantity must be positive");
            }
        }
    }
}
