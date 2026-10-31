package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.OrderConfirmationCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderConfirmedMessageParser {
    private final ObjectMapper mapper;

    public OrderConfirmationCommand parse(String key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("주문 확정 메시지 본문이 필요합니다.");
        }
        try {
            JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (root == null || !root.isObject() || !text(root, "eventType").equals("OrderConfirmed")) {
                throw new IllegalArgumentException("OrderConfirmed 메시지가 필요합니다.");
            }
            JsonNode version = root.path("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
                throw new IllegalArgumentException("지원하는 schemaVersion은 정수 1입니다.");
            }
            long orderId = positiveLong(root, "orderId");
            if (!Long.toString(orderId).equals(key)) {
                throw new IllegalArgumentException("메시지 key와 orderId가 일치해야 합니다.");
            }
            Instant confirmedAt = Instant.parse(text(root, "confirmedAt"));
            if (confirmedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException("confirmedAt은 저장된 주문 시각의 마이크로초 정밀도여야 합니다.");
            }
            JsonNode sourceItems = root.path("items");
            if (!sourceItems.isArray() || sourceItems.isEmpty()) {
                throw new IllegalArgumentException("구매 품목 배열이 필요합니다.");
            }
            List<OrderConfirmationCommand.Item> items = new ArrayList<>();
            for (JsonNode item : sourceItems) {
                long quantity = positiveLong(item, "quantity");
                if (quantity > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("구매 수량이 저장 가능한 범위를 초과했습니다.");
                }
                items.add(new OrderConfirmationCommand.Item(positiveLong(item, "productId"), (int) quantity));
            }
            return new OrderConfirmationCommand(text(root, "eventId"), orderId, confirmedAt, items);
        } catch (IOException | DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("주문 확정 메시지의 형식 또는 값이 올바르지 않습니다.", exception);
        }
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있지 않은 문자열이어야 합니다.");
        }
        return value.textValue();
    }

    private long positiveLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + "는 저장 가능한 양의 정수여야 합니다.");
        }
        return value.longValue();
    }
}
