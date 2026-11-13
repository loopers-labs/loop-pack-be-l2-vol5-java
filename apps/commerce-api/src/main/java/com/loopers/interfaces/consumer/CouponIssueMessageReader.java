package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

@Component
class CouponIssueMessageReader {

    private static final String EVENT_TYPE = "CouponIssue";
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    CouponIssueMessageReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CouponIssue read(byte[] value) {
        JsonNode root = parse(value);
        String eventType = requireText(root, "eventType");
        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalStateException("쿠폰 접수 메시지가 아니다: eventType=" + eventType);
        }
        int schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("지원하지 않는 쿠폰 접수 메시지 버전이다: schemaVersion=" + schemaVersion);
        }
        return new CouponIssue(requireLong(root, "campaignId"), requireLong(root, "userId"),
            requireInt(root, "issueNumber"), requireInstant(root, "reservedAt"));
    }

    private JsonNode parse(byte[] value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("쿠폰 접수 메시지가 JSON 객체가 아니다");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("쿠폰 접수 메시지를 읽을 수 없다", e);
        }
    }

    private static JsonNode require(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalStateException("쿠폰 접수 메시지에 " + field + " 가 없다");
        }
        return node;
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode node = require(root, field);
        if (!node.isTextual()) {
            throw new IllegalStateException("쿠폰 접수 메시지의 " + field + " 가 문자열이 아니다");
        }
        return node.asText();
    }

    private static long requireLong(JsonNode root, String field) {
        JsonNode node = require(root, field);
        if (!node.canConvertToLong()) {
            throw new IllegalStateException("쿠폰 접수 메시지의 " + field + " 가 정수가 아니다");
        }
        return node.asLong();
    }

    private static int requireInt(JsonNode root, String field) {
        JsonNode node = require(root, field);
        if (!node.canConvertToInt()) {
            throw new IllegalStateException("쿠폰 접수 메시지의 " + field + " 가 정수가 아니다");
        }
        return node.asInt();
    }

    private static Instant requireInstant(JsonNode root, String field) {
        try {
            return Instant.parse(requireText(root, field));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("쿠폰 접수 메시지의 " + field + " 가 시각이 아니다", e);
        }
    }
}
