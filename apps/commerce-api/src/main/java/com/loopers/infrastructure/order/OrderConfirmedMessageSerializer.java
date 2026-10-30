package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.order.OrderConfirmedPayload;
import java.time.format.DateTimeFormatter;

// 계약 필드를 직접 적어 공용 ObjectMapper 설정(이름 규칙·날짜 형식)이 바뀌어도 메시지 모양이 따라 바뀌지 않게 한다.
public final class OrderConfirmedMessageSerializer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OrderConfirmedMessageSerializer() {
    }

    public static String serialize(OrderConfirmedPayload payload) {
        ObjectNode message = JSON.createObjectNode()
            .put("eventType", OrderConfirmedPayload.EVENT_TYPE)
            .put("schemaVersion", OrderConfirmedPayload.SCHEMA_VERSION)
            .put("eventId", payload.eventId())
            .put("orderId", payload.orderId())
            .put("confirmedAt", DateTimeFormatter.ISO_INSTANT.format(payload.confirmedAt()));
        ArrayNode items = message.putArray("items");
        payload.items().forEach(item -> items.addObject()
            .put("productId", item.productId())
            .put("quantity", item.quantity()));
        try {
            return JSON.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 확정 메시지를 만들지 못했습니다. eventId=" + payload.eventId(), e);
        }
    }
}
