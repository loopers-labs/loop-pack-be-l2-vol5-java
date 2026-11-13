package com.loopers.interfaces.api.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * 쿠폰 발급 API 진입의 전역 Rate limiter 설정(설계서 13절). 초기 폭주를 막는 장치이며 동시 실행 수 제한(세마포어)과는 별개다.
 *
 * @param enabled        false이면 필터를 만들지 않는다. 동시 실행 수 제한 실험처럼 속도 제한을 가리고 싶을 때 끈다
 * @param limitForPeriod 한 주기(refreshPeriod) 안에 통과시키는 발급 요청 수. 사용자 구분 없이 전역 하나다
 * @param refreshPeriod  한도를 다시 채우는 주기
 */
@ConfigurationProperties("coupon.rate-limit")
public record CouponRateLimitProperties(boolean enabled, int limitForPeriod, Duration refreshPeriod) {
    public CouponRateLimitProperties {
        if (limitForPeriod < 1) {
            throw new IllegalArgumentException("coupon.rate-limit.limit-for-period는 1 이상이어야 한다");
        }
        Objects.requireNonNull(refreshPeriod, "coupon.rate-limit.refresh-period가 없다");
        if (refreshPeriod.isZero() || refreshPeriod.isNegative()) {
            throw new IllegalArgumentException("coupon.rate-limit.refresh-period는 0보다 커야 한다");
        }
    }
}
