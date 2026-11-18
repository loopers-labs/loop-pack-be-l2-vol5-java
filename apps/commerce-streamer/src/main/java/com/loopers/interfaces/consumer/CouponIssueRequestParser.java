package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.vo.CouponIssueRequested;
import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** coupon-issue-requests v1 메시지(commerce-api CouponIssueRequestMessage)를 접수 사실로 바꿈 (8주차 D-132, D-136) */
@RequiredArgsConstructor
@Component
public class CouponIssueRequestParser {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public CouponIssueRequested parse(String key, byte[] value) {
        JsonNode root = read(key, value);
        if (!root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").asInt() != SCHEMA_VERSION) {
            throw new RejectedEventException(RejectReason.UNSUPPORTED_EVENT, "key=" + key + " schemaVersion=" + root.path("schemaVersion"));
        }
        long campaignId = positiveLong(root, "campaignId", key);
        long userId = positiveLong(root, "userId", key);
        long sequence = positiveLong(root, "sequence", key);
        if (!(campaignId + ":" + userId).equals(key)) {
            throw new RejectedEventException(RejectReason.KEY_MISMATCH, "key=" + key + " campaignId=" + campaignId + " userId=" + userId);
        }
        return new CouponIssueRequested(campaignId, userId, sequence);
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

    private static long positiveLong(JsonNode root, String field, String key) {
        JsonNode node = root.path(field);
        if (!node.canConvertToLong() || !node.isIntegralNumber() || node.asLong() < 1) {
            throw invalid(key, field + " 가 1 이상의 정수가 아님");
        }
        return node.asLong();
    }

    private static RejectedEventException invalid(String key, String detail) {
        return new RejectedEventException(RejectReason.INVALID_FORMAT, "key=" + key + " " + detail);
    }
}
