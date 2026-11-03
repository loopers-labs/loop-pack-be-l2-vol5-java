package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.OrderConfirmedEvent;
import com.loopers.domain.purchase.PermanentEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * record 의 key·value(JSON bytes)를 OrderConfirmed 계약(설계 1절)으로 검증해 이벤트로 바꾼다.
 * 계약 위반은 모두 영구 오류다 (설계 D12).
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedEventParser {

    static final String EVENT_TYPE = "OrderConfirmed";
    static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public OrderConfirmedEvent parse(String key, byte[] value) {
        JsonNode root = readTree(value);

        String eventType = requiredText(root, "eventType");
        if (!EVENT_TYPE.equals(eventType)) {
            throw new PermanentEventException("지원하지 않는 eventType: " + eventType);
        }
        int schemaVersion = requiredInt(root, "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new PermanentEventException("지원하지 않는 schemaVersion: " + schemaVersion);
        }

        String eventId = requiredText(root, "eventId");
        long orderId = requiredLong(root, "orderId");
        if (!String.valueOf(orderId).equals(key)) {
            throw new PermanentEventException("record key 가 orderId 와 다릅니다. key=" + key + ", orderId=" + orderId + ", eventId=" + eventId);
        }

        ZonedDateTime confirmedAt;
        try {
            confirmedAt = ZonedDateTime.parse(requiredText(root, "confirmedAt"));
        } catch (DateTimeParseException e) {
            throw new PermanentEventException("confirmedAt 형식이 잘못됐습니다. eventId=" + eventId, e);
        }

        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            throw new PermanentEventException("items 가 배열이 아닙니다. eventId=" + eventId);
        }
        List<OrderConfirmedEvent.Item> items = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            items.add(new OrderConfirmedEvent.Item(requiredLong(itemNode, "productId"), requiredInt(itemNode, "quantity")));
        }

        return new OrderConfirmedEvent(eventId, orderId, confirmedAt, items);
    }

    private JsonNode readTree(byte[] value) {
        if (value == null) {
            throw new PermanentEventException("record value 가 없습니다.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(value);
        } catch (IOException e) {
            throw new PermanentEventException("JSON 을 읽을 수 없습니다: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new PermanentEventException("JSON 객체가 아닙니다.");
        }
        return root;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new PermanentEventException(field + " 가 없거나 문자열이 아닙니다.");
        }
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new PermanentEventException(field + " 가 없거나 정수가 아닙니다.");
        }
        return value.asLong();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new PermanentEventException(field + " 가 없거나 정수가 아닙니다.");
        }
        return value.asInt();
    }
}
