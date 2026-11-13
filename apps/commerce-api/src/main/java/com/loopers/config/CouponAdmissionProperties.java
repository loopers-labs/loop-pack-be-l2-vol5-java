package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 쿠폰 신규 발급 수용량 C. 기본 3은 적정 운영 용량의 실측값이 아니다. */
@ConfigurationProperties("commerce.coupon.admission")
public record CouponAdmissionProperties(
    @DefaultValue("3") int capacity
) {

    public CouponAdmissionProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("commerce.coupon.admission.capacity 는 1 이상의 정수여야 합니다. [capacity = " + capacity + "]");
        }
    }
}
