package com.loopers.coupon.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coupon.issue.admission")
public record CouponAdmissionProperties(int capacity, boolean enabled) {

    public CouponAdmissionProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("coupon issue admission capacity must be at least 1");
        }
    }
}
