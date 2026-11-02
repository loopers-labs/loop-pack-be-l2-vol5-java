package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * order-events 의 OrderConfirmed v1 메시지 형식을 검사해 확정 사실로 바꿈 (6주차 설계 2, 4.2).
 * 사실의 규칙(품목 · 수량 · 정밀도)은 OrderConfirmation 이 검사함
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedParser {

    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public OrderConfirmation parse(String key, byte[] value) {
        JsonNode root = read(key, value);
        String eventType = text(root, "eventType", key);
        int schemaVersion = integer(root, "schemaVersion", key);
        if (!EVENT_TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
            throw new RejectedEventException(RejectReason.UNSUPPORTED_EVENT, "key=" + key + " eventType=" + eventType + " schemaVersion=" + schemaVersion);
        }
        String eventId = text(root, "eventId", key);
        long orderId = longValue(root, "orderId", key);
        if (!String.valueOf(orderId).equals(key)) {
            throw new RejectedEventException(RejectReason.KEY_MISMATCH, "key=" + key + " orderId=" + orderId);
        }
        return new OrderConfirmation(eventId, orderId, confirmedAt(root, key), items(root, key));
    }

    private JsonNode read(String key, byte[] value) {
        try {
            JsonNode root = value == null ? null : objectMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid(key, "본문이 JSON 객체가 아님");
            }
            return root;
        } catch (IOException e) {
            throw invalid(key, "JSON 파싱 실패");
        }
    }

    private static ZonedDateTime confirmedAt(JsonNode root, String key) {
        String text = text(root, "confirmedAt", key);
        try {
            return Instant.parse(text).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw invalid(key, "confirmedAt=" + text);
        }
    }

    private static List<OrderConfirmation.Item> items(JsonNode root, String key) {
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw invalid(key, "items");
        }
        List<OrderConfirmation.Item> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new OrderConfirmation.Item(longValue(item, "productId", key), integer(item, "quantity", key)));
        }
        return result;
    }

    private static String text(JsonNode node, String field, String key) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(key, field);
        }
        return value.asText();
    }

    private static long longValue(JsonNode node, String field, String key) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(key, field);
        }
        return value.asLong();
    }

    private static int integer(JsonNode node, String field, String key) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(key, field);
        }
        return value.asInt();
    }

    private static RejectedEventException invalid(String key, String detail) {
        return new RejectedEventException(RejectReason.INVALID_FORMAT, "key=" + key + " " + detail);
    }
}
