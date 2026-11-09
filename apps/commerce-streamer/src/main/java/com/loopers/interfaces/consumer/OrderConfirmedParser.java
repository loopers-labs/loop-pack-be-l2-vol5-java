package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.InvalidOrderEventException;
import com.loopers.domain.purchase.OrderConfirmation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code order-events}의 record를 OrderConfirmed v1 계약으로 읽고 검증한다. 계약을 지키지 않으면 {@link InvalidOrderEventException}이다.
 * 모르는 eventType·schemaVersion은 건너뛰지 않고 오류로 드러낸다. 잘못 건너뛴 이벤트는 구매 활동에서 조용히 빠지기 때문이다.
 */
@RequiredArgsConstructor
@Component
public class OrderConfirmedParser {

    static final String EVENT_TYPE = "OrderConfirmed";
    static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public OrderConfirmation parseAndValidate(String key, byte[] value) {
        if (value == null) {
            throw new InvalidOrderEventException("본문이 비어 있습니다.");
        }
        JsonNode root = read(value);
        if (!root.isObject()) {
            throw new InvalidOrderEventException("본문이 JSON 객체가 아닙니다.");
        }
        if (!EVENT_TYPE.equals(text(root, "eventType"))) {
            throw new InvalidOrderEventException("지원하지 않는 eventType입니다: " + text(root, "eventType"));
        }
        if (integral(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new InvalidOrderEventException("지원하지 않는 schemaVersion입니다: " + root.get("schemaVersion"));
        }

        long orderId = integral(root, "orderId");
        if (key == null || !key.equals(String.valueOf(orderId))) {
            throw new InvalidOrderEventException("record key와 본문의 orderId가 다릅니다. key=" + key + ", orderId=" + orderId);
        }
        return new OrderConfirmation(text(root, "eventId"), orderId, instant(root, "confirmedAt"), items(root));
    }

    private JsonNode read(byte[] value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            throw new InvalidOrderEventException("JSON으로 읽을 수 없습니다: " + e.getMessage());
        }
    }

    private static List<OrderConfirmation.Item> items(JsonNode root) {
        JsonNode node = root.get("items");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new InvalidOrderEventException("items가 비어 있거나 배열이 아닙니다.");
        }
        List<OrderConfirmation.Item> items = new ArrayList<>();
        for (JsonNode item : node) {
            long quantity = integral(item, "quantity");
            if (quantity > Integer.MAX_VALUE) {
                throw new InvalidOrderEventException("quantity가 너무 큽니다: " + quantity);
            }
            items.add(new OrderConfirmation.Item(integral(item, "productId"), (int) quantity));
        }
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidOrderEventException(field + "가 없거나 문자열이 아닙니다.");
        }
        return value.asText();
    }

    private static long integral(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidOrderEventException(field + "가 없거나 정수가 아닙니다.");
        }
        return value.asLong();
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException e) {
            throw new InvalidOrderEventException(field + "를 시각으로 읽을 수 없습니다: " + node.get(field));
        }
    }
}
