package com.loopers.application.coupon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public record CouponIssueMessage(String eventType, Integer schemaVersion, Long campaignId, Long userId,
                                 Integer issueNumber, String reservedAt) {

    private static final String EVENT_TYPE = "CouponIssue";
    private static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter MICROS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    public static CouponIssueMessage from(CouponIssue issue) {
        return new CouponIssueMessage(EVENT_TYPE, SCHEMA_VERSION, issue.campaignId(), issue.userId(),
            issue.issueNumber(), MICROS.format(issue.reservedAt()));
    }

    public static CouponIssue read(ObjectMapper objectMapper, byte[] value) {
        try {
            return objectMapper.readValue(value, CouponIssueMessage.class).toIssue();
        } catch (IOException | NullPointerException | DateTimeParseException e) {
            throw new IllegalStateException("쿠폰 접수 메시지를 읽을 수 없다: " + e.getMessage(), e);
        }
    }

    public byte[] toBytes(ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(this);
    }

    private CouponIssue toIssue() {
        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalStateException("쿠폰 접수 메시지가 아니다: eventType=" + eventType);
        }
        if (schemaVersion == null || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalStateException("지원하지 않는 쿠폰 접수 메시지 버전이다: schemaVersion=" + schemaVersion);
        }
        if (campaignId == null || userId == null || issueNumber == null || reservedAt == null) {
            throw new IllegalStateException("쿠폰 접수 메시지에 빠진 필드가 있다: " + this);
        }
        return new CouponIssue(campaignId, userId, issueNumber, Instant.parse(reservedAt));
    }
}
