package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

record OrderConfirmedMessage(String eventType, int schemaVersion, String eventId, Long orderId,
                             String confirmedAt, List<Item> items) {

    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter MICROS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    record Item(Long productId, int quantity) {
    }

    static OrderConfirmedMessage from(OrderConfirmed event) {
        List<Item> items = event.items().stream()
            .map(item -> new Item(item.productId(), item.quantity()))
            .toList();
        return new OrderConfirmedMessage(EVENT_TYPE, SCHEMA_VERSION, event.eventId(), event.orderId(),
            MICROS.format(event.confirmedAt()), items);
    }

    byte[] toBytes(ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(this);
    }
}
