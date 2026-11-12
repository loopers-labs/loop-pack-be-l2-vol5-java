package com.loopers.infrastructure.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("coupon.issue.publish")
record CouponIssuePublishProperties(Duration ackTimeout) {

    CouponIssuePublishProperties {
        if (ackTimeout == null || ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("발행 확인 대기 상한은 양수여야 합니다: " + ackTimeout);
        }
    }
}
