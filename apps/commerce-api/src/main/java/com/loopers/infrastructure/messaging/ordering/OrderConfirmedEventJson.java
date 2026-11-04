package com.loopers.infrastructure.messaging.ordering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.application.ordering.event.OrderConfirmedEvent;
import java.util.Map;

public final class OrderConfirmedEventJson {
    private static final ObjectWriter WRITER = JsonMapper.builder().build().writer();

    private OrderConfirmedEventJson() {}

    public static byte[] encode(OrderConfirmedEvent event) throws JsonProcessingException {
        return WRITER.writeValueAsBytes(Map.of(
            "eventType", OrderConfirmedEvent.TYPE,
            "schemaVersion", OrderConfirmedEvent.VERSION,
            "eventId", event.eventId().toString(),
            "orderId", event.orderId(),
            "confirmedAt", event.confirmedAt().toString(),
            "items", event.items()
        ));
    }
}
