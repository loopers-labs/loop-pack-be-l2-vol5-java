package com.loopers.infrastructure.coupon.messaging;

import com.loopers.domain.coupon.vo.IssueRequested;

/** 접수 topic 의 계약. consumer(commerce-streamer)는 이 형식으로 읽는다 */
record CouponIssueRequestMessage(int schemaVersion, long campaignId, long userId, long sequence, String requestedAt) {

    static final int SCHEMA_VERSION = 1;

    static CouponIssueRequestMessage from(IssueRequested request) {
        return new CouponIssueRequestMessage(SCHEMA_VERSION, request.campaignId(), request.userId(),
            request.sequence(), request.requestedAt().toOffsetDateTime().toString());
    }
}
