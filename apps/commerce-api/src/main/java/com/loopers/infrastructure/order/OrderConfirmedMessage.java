package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderConfirmed;

import java.util.List;

/** Kafka JSON 경계. 시각은 serializer 설정과 무관하게 UTC ISO-8601 문자열로 전달한다. */
public record OrderConfirmedMessage(String eventType, int schemaVersion, String eventId, Long orderId,
                                    String confirmedAt, List<OrderConfirmed.Item> items) {

    public static OrderConfirmedMessage from(OrderConfirmed event) {
        return new OrderConfirmedMessage("OrderConfirmed", 1, event.eventId(), event.orderId(),
            event.confirmedAt().toString(), event.items());
    }
}
