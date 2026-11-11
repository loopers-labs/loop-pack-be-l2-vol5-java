package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("쿠폰 캠페인 생성은 이름·총량·혜택 경계를 지키고 ACTIVE·발급 수 0 으로 시작한다.")
class CouponCampaignModelTest {

    @DisplayName("이름을 trim 해 저장하고 ACTIVE·issuedCount 0 으로 만든다.")
    @Test
    void createsActiveCampaign() {
        CouponCampaignModel campaign = CouponCampaignModel.create("  봄 쿠폰  ", 5L, CouponBenefitType.FIXED_AMOUNT, 5_000L);

        assertAll(
            () -> assertThat(campaign.getName()).isEqualTo("봄 쿠폰"),
            () -> assertThat(campaign.getStatus()).isEqualTo(CouponCampaignStatus.ACTIVE),
            () -> assertThat(campaign.getIssuedCount()).isZero(),
            () -> assertThat(campaign.getTotalQuota()).isEqualTo(5L),
            () -> assertThat(campaign.getBenefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT),
            () -> assertThat(campaign.getBenefitValue()).isEqualTo(5_000L)
        );
    }

    @DisplayName("trim 후 1자·100자 이름은 허용하고 빈 이름·공백만·101자·null 은 거절한다.")
    @Test
    void nameBoundaries() {
        String hundred = "가".repeat(100);

        assertAll(
            () -> assertThat(create(" a ").getName()).isEqualTo("a"),
            () -> assertThat(create(" " + hundred + " ").getName()).isEqualTo(hundred),
            () -> assertRejected(() -> create("")),
            () -> assertRejected(() -> create("   ")),
            () -> assertRejected(() -> create("가".repeat(101))),
            () -> assertRejected(() -> create(null))
        );
    }

    @DisplayName("총량은 1~Long.MAX_VALUE 이다. 0·음수는 거절한다.")
    @Test
    void quotaBoundaries() {
        assertAll(
            () -> assertThat(CouponCampaignModel.create("c", 1L, CouponBenefitType.FIXED_AMOUNT, 1L).getTotalQuota()).isEqualTo(1L),
            () -> assertThat(CouponCampaignModel.create("c", Long.MAX_VALUE, CouponBenefitType.FIXED_AMOUNT, 1L).getTotalQuota())
                .isEqualTo(Long.MAX_VALUE),
            () -> assertRejected(() -> CouponCampaignModel.create("c", 0L, CouponBenefitType.FIXED_AMOUNT, 1L)),
            () -> assertRejected(() -> CouponCampaignModel.create("c", -1L, CouponBenefitType.FIXED_AMOUNT, 1L))
        );
    }

    @DisplayName("정액은 1~Long.MAX_VALUE 이며 상품 가격 상한을 적용하지 않는다. 0·음수는 거절한다.")
    @Test
    void fixedAmountBoundaries() {
        assertAll(
            () -> assertThat(CouponCampaignModel.create("c", 1L, CouponBenefitType.FIXED_AMOUNT, 1L).getBenefitValue()).isEqualTo(1L),
            () -> assertThat(CouponCampaignModel.create("c", 1L, CouponBenefitType.FIXED_AMOUNT, 100_000_001L).getBenefitValue())
                .isEqualTo(100_000_001L),
            () -> assertThat(CouponCampaignModel.create("c", 1L, CouponBenefitType.FIXED_AMOUNT, Long.MAX_VALUE).getBenefitValue())
                .isEqualTo(Long.MAX_VALUE),
            () -> assertRejected(() -> CouponCampaignModel.create("c", 1L, CouponBenefitType.FIXED_AMOUNT, 0L)),
            () -> assertRejected(() -> CouponCampaignModel.create("c", 1L, CouponBenefitType.FIXED_AMOUNT, -1L))
        );
    }

    @DisplayName("정률은 정수 1~100 이다.")
    @ParameterizedTest
    @ValueSource(longs = {1L, 50L, 100L})
    void percentageAccepted(long value) {
        assertThat(CouponCampaignModel.create("c", 1L, CouponBenefitType.PERCENTAGE, value).getBenefitValue()).isEqualTo(value);
    }

    @DisplayName("정률 0·101·음수는 거절한다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, 101L, -1L})
    void percentageRejected(long value) {
        assertRejected(() -> CouponCampaignModel.create("c", 1L, CouponBenefitType.PERCENTAGE, value));
    }

    @DisplayName("혜택 유형 null 은 거절한다.")
    @Test
    void rejectsNullBenefitType() {
        assertRejected(() -> CouponCampaignModel.create("c", 1L, null, 1L));
    }

    private static CouponCampaignModel create(String name) {
        return CouponCampaignModel.create(name, 1L, CouponBenefitType.FIXED_AMOUNT, 1L);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
    }
}
