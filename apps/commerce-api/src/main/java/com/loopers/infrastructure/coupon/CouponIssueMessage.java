package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

record CouponIssueMessage(String eventType, int schemaVersion, Long campaignId, Long userId, int issueNumber,
                          String reservedAt) {

    static final String EVENT_TYPE = "CouponIssue";
    static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter MICROS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    static CouponIssueMessage from(CouponIssue issue) {
        return new CouponIssueMessage(EVENT_TYPE, SCHEMA_VERSION, issue.campaignId(), issue.userId(),
            issue.issueNumber(), MICROS.format(issue.reservedAt()));
    }

    byte[] toBytes(ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(this);
    }
}
