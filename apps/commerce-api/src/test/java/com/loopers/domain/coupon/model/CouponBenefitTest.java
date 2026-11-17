package com.loopers.domain.coupon.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponBenefitTest {
    @Test
    @DisplayName("Unicode 설명의 500자 경계와 long 최대 정액 금액을 허용한다")
    void acceptsBenefitBoundaries() {
        // act
        CouponBenefit benefit = new CouponBenefit("  " + "🎟".repeat(500) + "  ", Long.MAX_VALUE);

        // assert
        assertThat(benefit.description()).isEqualTo("🎟".repeat(500));
        assertThat(benefit.discountAmount()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("공백·초과 길이 설명과 0 이하 금액을 거절한다")
    void rejectsInvalidBenefit() {
        // act / assert
        assertThatThrownBy(() -> new CouponBenefit(" ", 1L))
                .isInstanceOf(DomainException.class).extracting("errorCode")
                .isEqualTo(DomainErrorCode.COUPON_INVALID_BENEFIT);
        assertThatThrownBy(() -> new CouponBenefit("가".repeat(501), 1L))
                .isInstanceOf(DomainException.class).extracting("errorCode")
                .isEqualTo(DomainErrorCode.COUPON_INVALID_BENEFIT);
        assertThatThrownBy(() -> new CouponBenefit("혜택", 0L))
                .isInstanceOf(DomainException.class).extracting("errorCode")
                .isEqualTo(DomainErrorCode.NON_POSITIVE_MONEY);
        assertThatThrownBy(() -> new CouponBenefit("혜택", -1L))
                .isInstanceOf(DomainException.class).extracting("errorCode")
                .isEqualTo(DomainErrorCode.NON_POSITIVE_MONEY);
    }
}
