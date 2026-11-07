package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.application.order.OrderConfirmedPayloadSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** OrderConfirmed v1 계약(OrderConfirmedMessage)의 JSON. 여기서 만든 문자열이 그대로 Outbox payload 이고 record value 다. */
@RequiredArgsConstructor
@Component
public class OrderConfirmedJsonSerializer implements OrderConfirmedPayloadSerializer {
    private final ObjectMapper objectMapper;

    @Override
    public String serialize(OrderConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(OrderConfirmedMessage.from(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OrderConfirmed 직렬화 실패 [eventId = " + event.eventId() + "]", e);
        }
    }
}
