package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 쿠폰 대기 등록 입구 요청률 제한 시작 설정(commerce.coupon.rate-limit, 설계 §12.3·12.11).
 * 앱 인스턴스 하나에 limiter 하나이며 주기당 허가 수·갱신 주기만 설정한다. 허가 대기는 설정하지 않는다(0 고정).
 * 기본값(1초당 10개)은 테스트하기 쉬운 초기값이며 적정 처리량의 실측이 아니다. 0 이하·형식 오류는 기동 오류다.
 */
@ConfigurationProperties("commerce.coupon.rate-limit")
public record CouponRateLimitProperties(
    @DefaultValue("10") int limitForPeriod,
    @DefaultValue("1s") Duration refreshPeriod
) {

    public CouponRateLimitProperties {
        if (limitForPeriod < 1) {
            throw new IllegalArgumentException(
                "commerce.coupon.rate-limit.limit-for-period 는 1 이상의 정수여야 합니다. [limit-for-period = " + limitForPeriod + "]");
        }
        if (refreshPeriod == null || refreshPeriod.isNegative() || refreshPeriod.isZero()) {
            throw new IllegalArgumentException(
                "commerce.coupon.rate-limit.refresh-period 는 양의 기간이어야 합니다. [refresh-period = " + refreshPeriod + "]");
        }
    }
}
