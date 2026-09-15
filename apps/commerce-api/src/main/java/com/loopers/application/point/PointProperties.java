package com.loopers.application.point;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Period;

/**
 * 포인트 정책 설정값 (P-13). 충전 포인트의 유효기간은 0이나 음수일 수 없어 기동할 때 거절한다.
 */
@ConfigurationProperties(prefix = "point")
public record PointProperties(Period chargeValidity) {

    private static final Period DEFAULT_CHARGE_VALIDITY = Period.ofYears(5);

    public PointProperties {
        if (chargeValidity == null) {
            chargeValidity = DEFAULT_CHARGE_VALIDITY;
        }
        if (chargeValidity.isZero() || chargeValidity.isNegative()) {
            throw new IllegalArgumentException("point.charge-validity는 0보다 커야 합니다: " + chargeValidity);
        }
    }
}
