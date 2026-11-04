package com.loopers.infrastructure.messaging.ordering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.application.ordering.event.OrderConfirmedEvent;
import com.loopers.application.ordering.gateway.OrderEventEncoder;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedEventJson implements OrderEventEncoder {
    private static final ObjectWriter WRITER = JsonMapper.builder().build().writer();

    @Override
    public byte[] encodePayload(OrderConfirmedEvent event) {
        try {
            return encode(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("주문 확정 이벤트를 JSON으로 저장할 수 없습니다.", exception);
        }
    }

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
