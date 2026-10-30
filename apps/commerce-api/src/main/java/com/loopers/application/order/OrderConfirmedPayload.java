package com.loopers.application.order;

import java.time.Instant;
import java.util.List;

// order-events topic의 OrderConfirmed v1 계약(설계 27절). key는 record key이고 나머지가 payload 필드다.
public record OrderConfirmedPayload(String key, String eventId, long orderId, Instant confirmedAt, List<Item> items) {
    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public OrderConfirmedPayload {
        items = List.copyOf(items);
    }

    public record Item(long productId, int quantity) { }
}
