package com.loopers.interfaces.consumer;

import java.util.List;

/** order-events 의 OrderConfirmed value (JSON). 모르는 속성은 무시한다 (ObjectMapper 설정). 값 검증은 parser·도메인이 한다. */
public record OrderConfirmedMessage(
    String eventType,
    Integer schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {
    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public record Item(Long productId, Integer quantity) {
    }
}
