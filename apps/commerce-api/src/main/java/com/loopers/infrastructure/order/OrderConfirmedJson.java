package com.loopers.infrastructure.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.order.OrderConfirmedPayload;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * OrderConfirmed payload를 계약 JSON으로 쓴다(docs/week6/design.md 2번, docs/week6/contract/order-confirmed.v1.json).
 *
 * - 필드는 계약에 있는 것만 직접 넣는다. Spring의 ObjectMapper 설정이나 record 직렬화 규칙에 기대지 않는다(D-25).
 * - confirmedAt은 항상 UTC, 소수 6자리, 끝은 Z다. Instant.toString()은 끝자리 0을 지워(.123400 → .1234) 같은 시각이 글자로는
 *   달라질 수 있다. 값 자체는 Order가 이미 UTC·μs로 정해 둔다(D-6) — 여기서는 모양만 고정한다.
 */
final class OrderConfirmedJson {

    static final DateTimeFormatter CONFIRMED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderConfirmedJson() {}

    static String write(OrderConfirmedPayload payload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventType", payload.eventType());
        root.put("schemaVersion", payload.schemaVersion());
        root.put("eventId", payload.eventId());
        root.put("orderId", payload.orderId());
        root.put("confirmedAt", payload.confirmedAt().withZoneSameInstant(ZoneOffset.UTC).format(CONFIRMED_AT_FORMAT));
        ArrayNode items = root.putArray("items");
        for (OrderConfirmedPayload.Item item : payload.items()) {
            items.addObject()
                .put("productId", item.productId())
                .put("quantity", item.quantity());
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("[orderId = " + payload.orderId() + "] 주문 확정 이벤트를 JSON으로 쓰지 못했습니다.", e);
        }
    }
}
