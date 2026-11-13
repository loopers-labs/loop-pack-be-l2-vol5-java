package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupon.issue.worker")
record CouponIssueWorkerProperties(int concurrency) {

    CouponIssueWorkerProperties {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("워커의 동시 수는 양의 정수여야 합니다: " + concurrency);
        }
    }
}
