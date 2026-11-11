package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponTest {

    private static final Instant ISSUE_NOW = Instant.parse("2026-11-01T00:00:00Z");

    @DisplayName("미리 생성한 쿠폰은 캠페인에 속하고 사용자와 발급 시각은 비어 있다.")
    @Test
    void createsUnissuedCoupon() {
        Coupon coupon = Coupon.create(10L);

        assertThat(coupon.getId()).isNull();
        assertThat(coupon.getCampaignId()).isEqualTo(10L);
        assertThat(coupon.getUserId()).isNull();
        assertThat(coupon.getIssuedAt()).isNull();
        assertThat(coupon.isIssued()).isFalse();
    }

    @DisplayName("쿠폰은 캠페인 없이 생성할 수 없다.")
    @Test
    void rejectsMissingCampaign() {
        assertThatThrownBy(() -> Coupon.create(null))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }

    @DisplayName("발급은 사용자와 전달받은 시각을 함께 기록한다.")
    @Test
    void assignsUserAndIssueTimeTogether() {
        Coupon coupon = Coupon.create(10L);

        coupon.issueTo(100L, ISSUE_NOW);

        assertThat(coupon.getUserId()).isEqualTo(100L);
        assertThat(coupon.getIssuedAt()).isEqualTo(ISSUE_NOW);
        assertThat(coupon.isIssued()).isTrue();
    }

    @DisplayName("한 번 발급된 쿠폰은 같은 사용자나 다른 사용자에게 다시 배정하지 않는다.")
    @ParameterizedTest
    @ValueSource(longs = {100, 200})
    void preservesOriginalOwnerAndTimeWhenAlreadyIssued(long nextUserId) {
        Coupon coupon = Coupon.create(10L);
        coupon.issueTo(100L, ISSUE_NOW);

        assertThatThrownBy(() -> coupon.issueTo(nextUserId, ISSUE_NOW.plusSeconds(1)))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.CONFLICT));
        assertThat(coupon.getUserId()).isEqualTo(100L);
        assertThat(coupon.getIssuedAt()).isEqualTo(ISSUE_NOW);
    }

    @DisplayName("사용자 없이 발급하면 거절하고 미발급 상태를 보존한다.")
    @Test
    void rejectsMissingUserWithoutChangingState() {
        Coupon coupon = Coupon.create(10L);

        assertThatThrownBy(() -> coupon.issueTo(null, ISSUE_NOW))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
        assertThat(coupon.getUserId()).isNull();
        assertThat(coupon.getIssuedAt()).isNull();
    }

    @DisplayName("발급 시각이 누락되면 사용자도 기록하지 않는다.")
    @Test
    void preservesUnissuedStateWhenTimeIsMissing() {
        Coupon coupon = Coupon.create(10L);

        assertThatThrownBy(() -> coupon.issueTo(100L, null)).isInstanceOf(NullPointerException.class);
        assertThat(coupon.getUserId()).isNull();
        assertThat(coupon.getIssuedAt()).isNull();
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

    @DisplayName("사용자와 발급 시각 중 하나만 저장된 상태는 복원할 수 없다.")
    @Test
    void rejectsPartiallyIssuedState() {
        assertThatThrownBy(() -> Coupon.reconstitute(1L, 10L, 100L, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.reconstitute(1L, 10L, null, ISSUE_NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
