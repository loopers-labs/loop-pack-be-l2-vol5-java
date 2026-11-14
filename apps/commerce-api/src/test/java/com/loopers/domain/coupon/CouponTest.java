package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final Instant ISSUE_NOW = Instant.parse("2026-11-01T00:00:00Z");

    @DisplayName("쿠폰은 발급할 때 캠페인, 사용자, 발급 시각을 모두 갖고 만들어진다.")
    @Test
    void createsIssuedCoupon() {
        Coupon coupon = Coupon.create(10L, 100L, ISSUE_NOW);

        assertThat(coupon.getId()).isNull();
        assertThat(coupon.getCampaignId()).isEqualTo(10L);
        assertThat(coupon.getUserId()).isEqualTo(100L);
        assertThat(coupon.getIssuedAt()).isEqualTo(ISSUE_NOW);
    }

    @DisplayName("발급하는 쿠폰은 캠페인, 사용자, 발급 시각 중 하나라도 없으면 만들 수 없다.")
    @Test
    void rejectsIssuedCouponWithMissingValue() {
        assertThatThrownBy(() -> Coupon.create(null, 100L, ISSUE_NOW))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertThatThrownBy(() -> Coupon.create(10L, null, ISSUE_NOW))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertThatThrownBy(() -> Coupon.create(10L, 100L, null)).isInstanceOf(NullPointerException.class);
    }

    @DisplayName("저장된 쿠폰은 같은 식별자와 발급 상태로 복원한다.")
    @Test
    void restoresIssuedCoupon() {
        Coupon coupon = Coupon.reconstitute(1L, 10L, 100L, ISSUE_NOW);

        assertThat(coupon.getId()).isEqualTo(1L);
        assertThat(coupon.getCampaignId()).isEqualTo(10L);
        assertThat(coupon.getUserId()).isEqualTo(100L);
        assertThat(coupon.getIssuedAt()).isEqualTo(ISSUE_NOW);
    }

    @DisplayName("쿠폰 행은 발급된 쿠폰만 나타내므로, 사용자나 발급 시각이 없는 저장 상태는 복원할 수 없다.")
    @Test
    void rejectsStoredStateWithoutUserOrIssueTime() {
        assertThatThrownBy(() -> Coupon.reconstitute(1L, 10L, 100L, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.reconstitute(1L, 10L, null, ISSUE_NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.reconstitute(1L, 10L, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
