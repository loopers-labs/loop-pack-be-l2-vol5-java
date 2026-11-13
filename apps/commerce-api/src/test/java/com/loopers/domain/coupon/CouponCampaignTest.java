package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 캠페인이 발급 판단과 발급 수를 스스로 지키는지 본다(docs/week8/decisions.md INV-801, D-8, D-9, D-15).
 * 락은 여기서 보지 않는다 — 호출자가 FOR UPDATE로 읽은 뒤 부른다는 전제의 판단만 본다.
 */
class CouponCampaignTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-11T10:00:00Z");

    private static CouponBenefit benefit() {
        return CouponBenefit.of("5,000원 할인권", 5_000L);
    }

    @DisplayName("캠페인을 만들 때,")
    @Nested
    class Create {
        @DisplayName("올바른 값이면, 활성이고 발급 수 0이다.")
        @Test
        void createsActiveCampaignWithZeroIssued() {
            CouponCampaign campaign = new CouponCampaign("오픈 기념", 100, benefit());

            assertAll(
                () -> assertThat(campaign.getName()).isEqualTo("오픈 기념"),
                () -> assertThat(campaign.isActive()).isTrue(),
                () -> assertThat(campaign.getTotalQuota()).isEqualTo(100),
                () -> assertThat(campaign.getIssuedCount()).isZero(),
                () -> assertThat(campaign.getBenefit()).isEqualTo(benefit())
            );
        }

        @DisplayName("이름이 비었거나, 총 한도가 양수가 아니거나, 혜택이 없으면, BAD_REQUEST다.")
        @Test
        void rejectsInvalidInputs() {
            assertAll(
                () -> assertBadRequest(() -> new CouponCampaign(null, 10, benefit())),
                () -> assertBadRequest(() -> new CouponCampaign(" ", 10, benefit())),
                () -> assertBadRequest(() -> new CouponCampaign("행사", 0, benefit())),
                () -> assertBadRequest(() -> new CouponCampaign("행사", -1, benefit())),
                () -> assertBadRequest(() -> new CouponCampaign("행사", 10, null))
            );
        }

        @DisplayName("혜택 설명이 비었거나 할인 금액이 양수가 아니면, BAD_REQUEST다.")
        @Test
        void rejectsInvalidBenefit() {
            assertAll(
                () -> assertBadRequest(() -> CouponBenefit.of(null, 1_000L)),
                () -> assertBadRequest(() -> CouponBenefit.of("", 1_000L)),
                () -> assertBadRequest(() -> CouponBenefit.of("할인권", 0L)),
                () -> assertBadRequest(() -> CouponBenefit.of("할인권", -1L))
            );
        }
    }

    @DisplayName("쿠폰을 발급할 때,")
    @Nested
    class IssueTo {
        @DisplayName("활성이고 남아 있으면, 발급 수가 1 오르고 지금 혜택을 복사한 쿠폰을 돌려준다.")
        @Test
        void incrementsAndCopiesBenefit() {
            CouponCampaign campaign = new CouponCampaign("오픈 기념", 2, benefit());

            IssuedCoupon coupon = campaign.issueTo(7L, NOW);

            assertAll(
                () -> assertThat(campaign.getIssuedCount()).isEqualTo(1),
                () -> assertThat(coupon.getUserId()).isEqualTo(7L),
                () -> assertThat(coupon.getCampaignId()).isEqualTo(campaign.getId()),
                () -> assertThat(coupon.getIssuedAt()).isEqualTo(NOW),
                () -> assertThat(coupon.getBenefit()).isEqualTo(benefit()),
                () -> assertThat(coupon.getBenefit()).isNotSameAs(campaign.getBenefit())
            );
        }

        @DisplayName("총 한도만큼 나갔으면, 품절 메시지의 CONFLICT이고 발급 수는 그대로다.")
        @Test
        void rejectsWhenSoldOut() {
            CouponCampaign campaign = new CouponCampaign("오픈 기념", 1, benefit());
            campaign.issueTo(1L, NOW);

            CoreException exception = assertThrows(CoreException.class, () -> campaign.issueTo(2L, NOW));

            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(exception.getMessage()).isEqualTo(CouponCampaign.SOLD_OUT_MESSAGE),
                () -> assertThat(campaign.getIssuedCount()).isEqualTo(1)
            );
        }

        @DisplayName("비활성이면, 남아 있어도 비활성 메시지의 CONFLICT이고 발급 수는 그대로다.")
        @Test
        void rejectsWhenInactive() {
            CouponCampaign campaign = new CouponCampaign("오픈 기념", 10, benefit());
            campaign.deactivate();

            CoreException exception = assertThrows(CoreException.class, () -> campaign.issueTo(1L, NOW));

            assertAll(
                () -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT),
                () -> assertThat(exception.getMessage()).isEqualTo(CouponCampaign.INACTIVE_MESSAGE),
                () -> assertThat(campaign.getIssuedCount()).isZero()
            );
        }

        @DisplayName("비활성이면서 품절이면, 비활성으로 거절한다. 두 메시지는 서로 다르다.")
        @Test
        void inactiveTakesPrecedenceOverSoldOut() {
            CouponCampaign campaign = new CouponCampaign("오픈 기념", 1, benefit());
            campaign.issueTo(1L, NOW);
            campaign.deactivate();

            CoreException exception = assertThrows(CoreException.class, () -> campaign.issueTo(2L, NOW));

            assertAll(
                () -> assertThat(exception.getMessage()).isEqualTo(CouponCampaign.INACTIVE_MESSAGE),
                () -> assertThat(CouponCampaign.INACTIVE_MESSAGE).isNotEqualTo(CouponCampaign.SOLD_OUT_MESSAGE)
            );
        }

        @DisplayName("사용자 ID나 발급 시각이 없으면, BAD_REQUEST이고 발급 수는 그대로다.")
        @Test
        void rejectsMissingUserOrTime() {
            CouponCampaign campaign = new CouponCampaign("오픈 기념", 10, benefit());

            assertAll(
                () -> assertBadRequest(() -> campaign.issueTo(null, NOW)),
                () -> assertBadRequest(() -> campaign.issueTo(1L, null)),
                () -> assertThat(campaign.getIssuedCount()).isZero()
            );
        }
    }

    @DisplayName("발급 뒤 캠페인 혜택을 바꾸면, 이미 만든 쿠폰의 혜택은 발급 순간의 값 그대로다. (INV-806, D-15)")
    @Test
    void benefitChangeDoesNotAffectIssuedCoupon() {
        CouponCampaign campaign = new CouponCampaign("오픈 기념", 10, benefit());
        IssuedCoupon coupon = campaign.issueTo(1L, NOW);

        campaign.changeBenefit(CouponBenefit.of("3,000원 할인권", 3_000L));

        assertAll(
            () -> assertThat(coupon.getBenefit().getDescription()).isEqualTo("5,000원 할인권"),
            () -> assertThat(coupon.getBenefit().getDiscountAmount()).isEqualTo(5_000L),
            () -> assertThat(campaign.getBenefit().getDiscountAmount()).isEqualTo(3_000L),
            () -> assertThat(campaign.issueTo(2L, NOW).getBenefit().getDiscountAmount()).isEqualTo(3_000L)
        );
    }

    @DisplayName("비활성화는 멱등하고, 발급 수를 바꾸지 않는다.")
    @Test
    void deactivateIsIdempotent() {
        CouponCampaign campaign = new CouponCampaign("오픈 기념", 10, benefit());
        campaign.issueTo(1L, NOW);

        campaign.deactivate();
        campaign.deactivate();

        assertAll(
            () -> assertThat(campaign.isActive()).isFalse(),
            () -> assertThat(campaign.getIssuedCount()).isEqualTo(1)
        );
    }

    private static void assertBadRequest(Executable executable) {
        CoreException exception = assertThrows(CoreException.class, executable);
        assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
    }
}
