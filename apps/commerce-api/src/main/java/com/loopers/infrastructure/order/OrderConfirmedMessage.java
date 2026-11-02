package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedEvent;

import java.util.List;

/**
 * order-events topic 의 OrderConfirmed value (JSON). 계약: eventType "OrderConfirmed", schemaVersion 1. record key 는 orderId 문자열.
 * confirmedAt 은 UTC ISO-8601 문자열("2026-09-07T01:00:00.123456Z")로 보낸다. Instant 를 그대로 두면 Kafka JsonSerializer 가 숫자 timestamp 로 쓴다.
 * 카드·PG 비밀·사용자 정보는 넣지 않는다.
 */
public record OrderConfirmedMessage(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {
    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public record Item(Long productId, Integer quantity) {
    }

    public static OrderConfirmedMessage from(OrderConfirmedEvent event) {
        return new OrderConfirmedMessage(
            EVENT_TYPE,
            SCHEMA_VERSION,
            event.eventId(),
            event.orderId(),
            event.confirmedAt().toString(),
            event.items().stream().map(item -> new Item(item.productId(), item.quantity())).toList()
        );
    }
}
