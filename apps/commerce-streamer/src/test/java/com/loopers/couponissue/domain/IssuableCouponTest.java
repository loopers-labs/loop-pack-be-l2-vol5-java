package com.loopers.couponissue.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPN-07 (streamer 쪽 구현): 만료 시각 = FIXED_PERIOD는 끝 시각, DAYS_FROM_ISSUE는 발급 시각 + 일수.
 * commerce-api Coupon과 같은 규칙을 따로 구현했으므로 여기서도 리터럴로 확인한다.
 */
class IssuableCouponTest {

    private static final ZonedDateTime ISSUED_AT = ZonedDateTime.parse("2026-11-13T10:00:05+09:00");

    @DisplayName("FIXED_PERIOD는 끝 시각이다.")
    @Test
    void fixedPeriodExpiresAtUntil() {
        ZonedDateTime until = ZonedDateTime.parse("2026-11-30T00:00:00+09:00");
        IssuableCoupon coupon = new IssuableCoupon(1L, 1L, IssuableCoupon.ValidityType.FIXED_PERIOD, until, null);

        assertThat(coupon.expiresAtWhenIssuedAt(ISSUED_AT)).isEqualTo(until);
    }

    @DisplayName("DAYS_FROM_ISSUE는 발급 시각 + 일수다 (7일 → 11-20 10:00:05).")
    @Test
    void daysFromIssue() {
        IssuableCoupon coupon = new IssuableCoupon(1L, 1L, IssuableCoupon.ValidityType.DAYS_FROM_ISSUE, null, 7);

        assertThat(coupon.expiresAtWhenIssuedAt(ISSUED_AT)).isEqualTo(ZonedDateTime.parse("2026-11-20T10:00:05+09:00"));
    }
}
