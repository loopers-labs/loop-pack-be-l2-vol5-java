package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchasedItem;
import com.loopers.interfaces.consumer.OrderEventParseResult.Invalid;
import com.loopers.interfaces.consumer.OrderEventParseResult.Parsed;
import com.loopers.interfaces.consumer.OrderEventParseResult.Reason;
import com.loopers.interfaces.consumer.OrderEventParseResult.Unsupported;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OrderConfirmedEventParser {

    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_EVENT_ID_LENGTH = 255;

    // 이벤트 계약은 API용 ObjectMapper 설정과 따로 고정한다. 소수 수량이나 문자열 숫자를 정수로 바꿔 받아들이지 않는다.
    private final JsonMapper jsonMapper = JsonMapper.builder()
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public OrderEventParseResult parse(String key, byte[] payload) {
        if (payload == null) {
            return new Invalid(Reason.MALFORMED_JSON, "빈 값", null, null);
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(payload);
        } catch (IOException e) {
            return new Invalid(Reason.MALFORMED_JSON, "JSON 해석 실패: " + e.getMessage(), null, null);
        }
        if (root == null || !root.isObject()) {
            return new Invalid(Reason.MALFORMED_JSON, "JSON 객체가 아님", null, null);
        }

        String eventId = textOrNull(root.get("eventId"));
        Long orderId = integralOrNull(root.get("orderId"));
        // 버전을 먼저 본다. 다른 버전은 필드 구성이 다를 수 있어 v1 기준으로 검사하면 안 된다.
        JsonNode eventType = root.get("eventType");
        if (eventType == null || eventType.isNull()) {
            return new Invalid(Reason.MISSING_FIELD, "eventType 누락", eventId, orderId);
        }
        if (!eventType.isTextual()) {
            return new Invalid(Reason.INVALID_FIELD, "eventType이 문자열이 아님", eventId, orderId);
        }
        if (!EVENT_TYPE.equals(eventType.asText())) {
            return new Unsupported("지원하지 않는 eventType: " + eventType.asText());
        }
        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || schemaVersion.isNull()) {
            return new Invalid(Reason.MISSING_FIELD, "schemaVersion 누락", eventId, orderId);
        }
        if (!schemaVersion.isInt()) {
            return new Invalid(Reason.INVALID_FIELD, "schemaVersion이 정수가 아님", eventId, orderId);
        }
        if (schemaVersion.intValue() != SCHEMA_VERSION) {
            return new Unsupported("지원하지 않는 schemaVersion: " + schemaVersion.intValue());
        }

        OrderConfirmedV1Message message;
        try {
            message = jsonMapper.treeToValue(root, OrderConfirmedV1Message.class);
        } catch (JsonProcessingException e) {
            return new Invalid(Reason.INVALID_FIELD, "필드 형식 오류: " + e.getOriginalMessage(), eventId, orderId);
        }
        return validate(key, message, eventId, orderId);
    }

    private OrderEventParseResult validate(String key, OrderConfirmedV1Message message, String eventId, Long orderId) {
        if (message.eventId() == null || message.eventId().isBlank()) {
            return new Invalid(Reason.MISSING_FIELD, "eventId 누락", eventId, orderId);
        }
        if (message.eventId().length() > MAX_EVENT_ID_LENGTH) {
            return new Invalid(Reason.INVALID_FIELD, "eventId가 " + MAX_EVENT_ID_LENGTH + "자를 넘음", null, orderId);
        }
        if (message.orderId() == null) {
            return new Invalid(Reason.MISSING_FIELD, "orderId 누락", eventId, null);
        }
        if (message.confirmedAt() == null) {
            return new Invalid(Reason.MISSING_FIELD, "confirmedAt 누락", eventId, orderId);
        }
        Instant confirmedAt;
        try {
            confirmedAt = OffsetDateTime.parse(message.confirmedAt()).toInstant();
        } catch (DateTimeParseException e) {
            return new Invalid(Reason.INVALID_FIELD, "confirmedAt 형식 오류: " + message.confirmedAt(), eventId, orderId);
        }
        if (message.items() == null) {
            return new Invalid(Reason.MISSING_FIELD, "items 누락", eventId, orderId);
        }
        if (message.items().isEmpty()) {
            return new Invalid(Reason.EMPTY_ITEMS, "items 비어 있음", eventId, orderId);
        }
        List<PurchasedItem> items = new ArrayList<PurchasedItem>();
        Set<Long> productIds = new HashSet<Long>();
        for (OrderConfirmedV1Message.Item item : message.items()) {
            if (item == null || item.productId() == null || item.quantity() == null) {
                return new Invalid(Reason.MISSING_FIELD, "items의 productId·quantity 누락", eventId, orderId);
            }
            if (item.quantity() <= 0) {
                return new Invalid(Reason.NON_POSITIVE_QUANTITY, "상품 " + item.productId() + " 수량 " + item.quantity(),
                    eventId, orderId);
            }
            if (!productIds.add(item.productId())) {
                return new Invalid(Reason.DUPLICATE_PRODUCT, "상품 " + item.productId() + " 중복", eventId, orderId);
            }
            items.add(new PurchasedItem(item.productId(), item.quantity()));
        }
        if (!String.valueOf(message.orderId()).equals(key)) {
            return new Invalid(Reason.KEY_MISMATCH, "key " + key + " ≠ orderId " + message.orderId(), eventId, orderId);
        }
        return new Parsed(new OrderConfirmedEvent(message.eventId(), message.orderId(), confirmedAt, items));
    }

    private String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    private Long integralOrNull(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        return node.longValue();
    }
}
