package com.loopers.config.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coupon.admission")
public record CouponAdmissionProperties(int maxConcurrency) {
    public CouponAdmissionProperties {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("coupon.admission.max-concurrency must be positive");
        }
    }
}
