package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("쿠폰 발급 행은 양수 참조 ID 와 유형별 혜택 범위를 검증한다.")
class CouponModelTest {

    @DisplayName("참조 ID 와 발급 당시 혜택을 그대로 담는다.")
    @Test
    void issues() {
        CouponModel coupon = CouponModel.issue(3L, 7L, CouponBenefitType.PERCENTAGE, 100L);

        assertAll(
            () -> assertThat(coupon.getUserId()).isEqualTo(3L),
            () -> assertThat(coupon.getCampaignId()).isEqualTo(7L),
            () -> assertThat(coupon.getBenefitType()).isEqualTo(CouponBenefitType.PERCENTAGE),
            () -> assertThat(coupon.getBenefitValue()).isEqualTo(100L)
        );
    }

    @DisplayName("null·0·음수 참조 ID, 범위 밖 혜택, null 유형은 거절한다.")
    @Test
    void rejectsInvalidValues() {
        assertAll(
            () -> assertRejected(() -> CouponModel.issue(null, 1L, CouponBenefitType.FIXED_AMOUNT, 1L)),
            () -> assertRejected(() -> CouponModel.issue(0L, 1L, CouponBenefitType.FIXED_AMOUNT, 1L)),
            () -> assertRejected(() -> CouponModel.issue(1L, null, CouponBenefitType.FIXED_AMOUNT, 1L)),
            () -> assertRejected(() -> CouponModel.issue(1L, -1L, CouponBenefitType.FIXED_AMOUNT, 1L)),
            () -> assertRejected(() -> CouponModel.issue(1L, 1L, CouponBenefitType.FIXED_AMOUNT, 0L)),
            () -> assertRejected(() -> CouponModel.issue(1L, 1L, CouponBenefitType.PERCENTAGE, 101L)),
            () -> assertRejected(() -> CouponModel.issue(1L, 1L, null, 1L))
        );
    }

    @DisplayName("정액 Long.MAX_VALUE 와 정률 1 은 허용한다.")
    @Test
    void acceptsBoundaries() {
        assertAll(
            () -> assertThat(CouponModel.issue(1L, 1L, CouponBenefitType.FIXED_AMOUNT, Long.MAX_VALUE).getBenefitValue())
                .isEqualTo(Long.MAX_VALUE),
            () -> assertThat(CouponModel.issue(1L, 1L, CouponBenefitType.PERCENTAGE, 1L).getBenefitValue()).isEqualTo(1L)
        );
    }

    private static void assertRejected(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
    }
}
