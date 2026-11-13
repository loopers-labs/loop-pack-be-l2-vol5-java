package com.loopers.domain.coupon.entity;

import com.loopers.domain.coupon.enums.CampaignStatus;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class CouponCampaignTest {

    private static CouponCampaign campaign(int totalQuota) {
        return new CouponCampaign("행사", totalQuota, DiscountType.FIXED_AMOUNT, 5_000L, "5,000원 할인권");
    }

    @DisplayName("캠페인을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("값이 범위 안이면, READY 상태와 발급 수 0 으로 생성된다.")
        @Test
        void createsReadyCampaign() {
            // act
            CouponCampaign campaign = campaign(5);

            // assert
            assertAll(
                () -> assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.READY),
                () -> assertThat(campaign.getIssuedCount()).isZero(),
                () -> assertThat(campaign.getTotalQuota()).isEqualTo(5)
            );
        }

        @DisplayName("이름이 비었으면, INVALID_CAMPAIGN_NAME 예외가 발생한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" "})
        void rejectsBlankName(String name) {
            assertThatThrownBy(() -> new CouponCampaign(name, 5, DiscountType.FIXED_AMOUNT, 5_000L, "설명"))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_CAMPAIGN_NAME));
        }

        @DisplayName("이름이 50자면 생성되고 51자면, INVALID_CAMPAIGN_NAME 예외가 발생한다.")
        @Test
        void limitsNameToFiftyChars() {
            assertAll(
                () -> assertThat(new CouponCampaign("가".repeat(50), 5, DiscountType.FIXED_AMOUNT, 5_000L, "설명").getName())
                    .hasSize(50),
                () -> assertThatThrownBy(() -> new CouponCampaign("가".repeat(51), 5, DiscountType.FIXED_AMOUNT, 5_000L, "설명"))
                    .isInstanceOfSatisfying(CoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_CAMPAIGN_NAME))
            );
        }

        @DisplayName("총 발급 한도가 1 보다 작으면, INVALID_TOTAL_QUOTA 예외가 발생한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void rejectsQuotaBelowOne(int totalQuota) {
            assertThatThrownBy(() -> campaign(totalQuota))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_TOTAL_QUOTA));
        }

        @DisplayName("정액은 1원 미만, 정률은 1% 미만이거나 100% 초과면, INVALID_DISCOUNT 예외가 발생한다.")
        @Test
        void rejectsDiscountOutOfRange() {
            assertAll(
                () -> assertThatThrownBy(() -> new CouponCampaign("행사", 5, DiscountType.FIXED_AMOUNT, 0L, "설명"))
                    .isInstanceOfSatisfying(CoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_DISCOUNT)),
                () -> assertThatThrownBy(() -> new CouponCampaign("행사", 5, DiscountType.PERCENTAGE, 101L, "설명"))
                    .isInstanceOfSatisfying(CoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_DISCOUNT)),
                () -> assertThatThrownBy(() -> new CouponCampaign("행사", 5, null, 10L, "설명"))
                    .isInstanceOfSatisfying(CoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_DISCOUNT))
            );
        }

        @DisplayName("정률 100% 는 허용된다.")
        @Test
        void allowsFullPercentage() {
            CouponCampaign campaign = new CouponCampaign("행사", 5, DiscountType.PERCENTAGE, 100L, "전액 할인");

            assertThat(campaign.getDiscountValue()).isEqualTo(100L);
        }

        @DisplayName("설명이 비었으면, INVALID_COUPON_DESCRIPTION 예외가 발생한다.")
        @ParameterizedTest
        @NullAndEmptySource
        void rejectsBlankDescription(String description) {
            assertThatThrownBy(() -> new CouponCampaign("행사", 5, DiscountType.FIXED_AMOUNT, 5_000L, description))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.INVALID_COUPON_DESCRIPTION));
        }
    }

    @DisplayName("상태를 바꿀 때, ")
    @Nested
    class Transition {
        @DisplayName("READY → OPEN → CLOSED 로 바뀌고, 같은 상태로 다시 바꾸면 그대로 둔다. (D-151, D-166)")
        @Test
        void movesForwardAndIsIdempotent() {
            // arrange
            CouponCampaign campaign = campaign(5);

            // act & assert
            campaign.open();
            campaign.open();
            assertThat(campaign.isOpen()).isTrue();

            campaign.close();
            campaign.close();
            assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.CLOSED);
        }

        @DisplayName("READY 를 바로 닫거나 CLOSED 를 다시 열면, 예외가 발생한다.")
        @Test
        void rejectsSkippingOrReopening() {
            // arrange
            CouponCampaign ready = campaign(5);
            CouponCampaign closed = campaign(5);
            closed.open();
            closed.close();

            // act & assert
            assertAll(
                () -> assertThatThrownBy(ready::close).isInstanceOf(IllegalStateException.class),
                () -> assertThatThrownBy(closed::open).isInstanceOf(IllegalStateException.class)
            );
        }
    }

    @DisplayName("예비 범위는 한도 + 한도의 비율(올림, 최소 1명)이다. (D-122)")
    @Test
    void reserveLimitIsQuotaPlusRatio_atLeastOne() {
        assertAll(
            () -> assertThat(campaign(100).reserveLimit(0.1)).isEqualTo(110),
            () -> assertThat(campaign(5).reserveLimit(0.1)).isEqualTo(6),
            () -> assertThat(campaign(5).reserveLimit(0.0)).isEqualTo(6),
            () -> assertThatThrownBy(() -> campaign(5).reserveLimit(-0.1)).isInstanceOf(IllegalArgumentException.class)
        );
    }

    @DisplayName("슬롯은 1 부터 한도까지만 있다. (D-141)")
    @Test
    void hasSlotsFromOneToQuota() {
        CouponCampaign campaign = campaign(5);

        assertAll(
            () -> assertThat(campaign.hasSlot(1)).isTrue(),
            () -> assertThat(campaign.hasSlot(5)).isTrue(),
            () -> assertThat(campaign.hasSlot(0)).isFalse(),
            () -> assertThat(campaign.hasSlot(6)).isFalse()
        );
    }
}
