package com.loopers.infrastructure.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupon.admission")
record CouponAdmissionProperties(int capacity) {

    CouponAdmissionProperties {
        if (capacity <= 0) {
            throw new IllegalArgumentException("수용량은 양의 정수여야 합니다: " + capacity);
        }
    }
}
