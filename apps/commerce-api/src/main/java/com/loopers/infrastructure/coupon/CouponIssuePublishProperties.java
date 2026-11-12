package com.loopers.infrastructure.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("coupon.issue.publish")
record CouponIssuePublishProperties(Duration ackTimeout, int maxInFlight) {

    CouponIssuePublishProperties {
        if (ackTimeout == null || ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("발행 확인 대기 상한은 양수여야 합니다: " + ackTimeout);
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("동시에 확인을 기다리는 수의 상한은 양의 정수여야 합니다: " + maxInFlight);
        }
    }
}
