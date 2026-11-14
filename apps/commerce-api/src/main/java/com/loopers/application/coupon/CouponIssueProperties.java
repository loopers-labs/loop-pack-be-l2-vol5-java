package com.loopers.application.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupon.issue")
public record CouponIssueProperties(Integer capacity, Admission admission) {

    public CouponIssueProperties {
        if (capacity != null && capacity < 1) {
            throw new IllegalArgumentException("쿠폰 발급 동시 수용량은 1 이상이어야 합니다.");
        }
        if (admission == null) {
            admission = new Admission(false);
        }
        if (admission.openOnStartup() && capacity == null) {
            throw new IllegalArgumentException("쿠폰 발급 수용을 열려면 동시 수용량 설정이 필요합니다.");
        }
    }

    public record Admission(boolean openOnStartup) {}

    public int requireCapacity() {
        if (capacity == null) {
            throw new IllegalStateException("쿠폰 발급 수용이 열렸지만 동시 수용량이 설정되지 않았습니다.");
        }
        return capacity;
    }
}
