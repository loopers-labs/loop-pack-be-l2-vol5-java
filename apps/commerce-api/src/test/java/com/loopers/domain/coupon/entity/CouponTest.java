package com.loopers.domain.coupon.entity;

import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class CouponTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-13T10:00:00+09:00");

    private static CouponCampaign openCampaign() {
        CouponCampaign campaign = new CouponCampaign("행사", 5, DiscountType.FIXED_AMOUNT, 5_000L, "5,000원 할인권");
        campaign.open();
        return campaign;
    }

    @DisplayName("발급하면, 캠페인의 혜택을 쿠폰에 복사해 둔다. (D-150)")
    @Test
    void copiesBenefitFromCampaign() {
        // act
        Coupon coupon = Coupon.issue(openCampaign(), 7L, 3, NOW);

        // assert
        assertAll(
            () -> assertThat(coupon.getUserId()).isEqualTo(7L),
            () -> assertThat(coupon.getSlotNo()).isEqualTo(3),
            () -> assertThat(coupon.getDiscountType()).isEqualTo(DiscountType.FIXED_AMOUNT),
            () -> assertThat(coupon.getDiscountValue()).isEqualTo(5_000L),
            () -> assertThat(coupon.getDescription()).isEqualTo("5,000원 할인권"),
            () -> assertThat(coupon.getIssuedAt()).isEqualTo(NOW)
        );
    }

    @DisplayName("열리지 않은 캠페인이면, CAMPAIGN_NOT_ACTIVE 예외가 발생한다. (D-124)")
    @Test
    void rejectsCampaignNotOpen() {
        CouponCampaign ready = new CouponCampaign("행사", 5, DiscountType.FIXED_AMOUNT, 5_000L, "설명");

        assertThatThrownBy(() -> Coupon.issue(ready, 7L, 1, NOW))
            .isInstanceOfSatisfying(CoreException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_ACTIVE));
    }

    @DisplayName("한도 밖 슬롯이면, 슬롯 배정 버그로 보고 업무 예외가 아닌 예외가 발생한다. (D-126, D-141)")
    @Test
    void rejectsSlotOutOfQuota() {
        assertThatThrownBy(() -> Coupon.issue(openCampaign(), 7L, 6, NOW))
            .isInstanceOf(IllegalStateException.class);
    }
}
