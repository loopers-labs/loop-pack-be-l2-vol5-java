package com.loopers.infrastructure.order.messaging;

import com.loopers.domain.order.vo.OrderConfirmed;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * order-events 의 OrderConfirmed v1 계약 (6주차 설계 2).
 * 시각은 직렬화기 설정에 맡기지 않고 UTC 마이크로초 6자리 문자열로 고정함 (D-83)
 */
public record OrderConfirmedMessage(
    String eventType,
    int schemaVersion,
    String eventId,
    Long orderId,
    String confirmedAt,
    List<Item> items
) {

    static final String EVENT_TYPE = "OrderConfirmed";
    static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter CONFIRMED_AT_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    public record Item(Long productId, int quantity) {}

    static OrderConfirmedMessage from(OrderConfirmed event) {
        List<Item> items = event.items().stream()
            .map(item -> new Item(item.productId(), item.quantity()))
            .toList();
        return new OrderConfirmedMessage(
            EVENT_TYPE,
            SCHEMA_VERSION,
            event.eventId(),
            event.orderId(),
            event.confirmedAt().withZoneSameInstant(ZoneOffset.UTC).format(CONFIRMED_AT_FORMAT),
            items
        );
    }
}
