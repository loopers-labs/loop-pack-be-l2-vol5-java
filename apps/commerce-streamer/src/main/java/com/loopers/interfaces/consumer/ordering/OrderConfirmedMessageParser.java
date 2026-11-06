package com.loopers.interfaces.consumer.ordering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.interfaces.consumer.ordering.dto.OrderConfirmedMessage;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

public final class OrderConfirmedMessageParser {
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
    private static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    public OrderConfirmedMessage parse(String key, byte[] value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("주문 이벤트 키와 값이 필요합니다.");
        }
        JsonNode root = readObject(value);
        if (!"OrderConfirmed".equals(root.path("eventType").textValue()) || positiveInt(root, "schemaVersion") != 1) {
            throw new IllegalArgumentException("지원하지 않는 주문 이벤트 계약입니다.");
        }
        long orderId = positiveLong(root, "orderId");
        if (!Long.toString(orderId).equals(key)) {
            throw new IllegalArgumentException("주문 이벤트 키와 주문 ID가 다릅니다.");
        }
        var quantities = new TreeMap<Long, Integer>();
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalArgumentException("주문 이벤트에 품목 배열이 필요합니다.");
        }
        for (JsonNode item : items) {
            try {
                quantities.merge(positiveLong(item, "productId"), positiveInt(item, "quantity"), Math::addExact);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("주문 이벤트의 상품별 수량이 범위를 넘었습니다.");
            }
        }
        var normalized = quantities.entrySet().stream()
            .map(entry -> new OrderConfirmedMessage.Item(entry.getKey(), entry.getValue()))
            .toList();
        return new OrderConfirmedMessage(eventId(root), orderId, confirmedAt(root), normalized);
    }

    private JsonNode readObject(byte[] value) {
        try {
            var utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
            JsonNode root = MAPPER.readTree(utf8);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("주문 이벤트는 JSON 객체여야 합니다.");
            }
            return root;
        } catch (JsonProcessingException | CharacterCodingException ex) {
            throw new IllegalArgumentException("주문 이벤트의 UTF-8 JSON 형식이 올바르지 않습니다.");
        }
    }

    private UUID eventId(JsonNode root) {
        String id = root.path("eventId").textValue();
        if (id == null || !UUID_FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException("주문 이벤트 ID는 소문자 표준 UUID여야 합니다.");
        }
        return UUID.fromString(id);
    }

    private Instant confirmedAt(JsonNode root) {
        String time = root.path("confirmedAt").textValue();
        if (time == null) {
            throw new IllegalArgumentException("주문 확정 시각이 필요합니다.");
        }
        try {
            Instant instant = OffsetDateTime.parse(time).toInstant();
            if (instant.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException("주문 확정 시각은 마이크로초 정밀도여야 합니다.");
            }
            return instant;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("주문 확정 시각에는 ISO-8601 시간대가 필요합니다.");
        }
    }

    private long positiveLong(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("주문 이벤트에 유효한 양의 정수 필드가 필요합니다. " + field);
        }
        return value.longValue();
    }

    private int positiveInt(JsonNode root, String field) {
        long value = positiveLong(root, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("주문 이벤트의 정수 필드가 범위를 넘었습니다. " + field);
        }
        return (int) value;
    }
}
