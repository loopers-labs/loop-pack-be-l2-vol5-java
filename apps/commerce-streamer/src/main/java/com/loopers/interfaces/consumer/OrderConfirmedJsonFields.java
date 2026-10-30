package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;

final class OrderConfirmedJsonFields {
    private OrderConfirmedJsonFields() {}

    static String text(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException("확정 이벤트 문자열 필드가 유효하지 않습니다: " + field);
        }
        return node.textValue();
    }

    static long integer(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException("확정 이벤트 정수 필드가 유효하지 않습니다: " + field);
        }
        return node.longValue();
    }
}
