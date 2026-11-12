package com.loopers.coupon.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponCampaignTest {

    private static final ZonedDateTime START = ZonedDateTime.parse("2026-11-13T10:00:00+09:00");
    private static final ZonedDateTime END = ZonedDateTime.parse("2026-11-13T11:00:00+09:00");

    @DisplayName("캠페인을 만들 때, ")
    @Nested
    class Create {

        @DisplayName("CPN-01 이름 100자(앞뒤 공백 제거), 당첨자 1명은 허용하고 발급 수는 0에서 시작한다.")
        @Test
        void acceptsBoundaryValues() {
            CouponCampaign campaign = new CouponCampaign(" " + "가".repeat(100) + " ", 1, 1, START, END);

            assertThat(campaign.getName()).hasSize(100);
            assertThat(campaign.getTotalQuantity()).isEqualTo(1);
            assertThat(campaign.getQueueCapacity()).isEqualTo(1);
            assertThat(campaign.getIssuedCount()).isZero();
        }

        @DisplayName("CPN-01 공백뿐인 이름, 101자 이름, 당첨자 0명은 거절한다.")
        @Test
        void rejectsInvalidNameOrQuantity() {
            assertBadRequest(() -> new CouponCampaign("  ", 100, null, START, END));
            assertBadRequest(() -> new CouponCampaign("가".repeat(101), 100, null, START, END));
            assertBadRequest(() -> new CouponCampaign("선착순", 0, null, START, END));
        }

        @DisplayName("CPN-01 시작이 마감과 같거나 늦으면 거절한다.")
        @Test
        void rejectsStartNotBeforeEnd() {
            assertBadRequest(() -> new CouponCampaign("선착순", 100, null, END, END));
            assertBadRequest(() -> new CouponCampaign("선착순", 100, null, END, START));
            assertBadRequest(() -> new CouponCampaign("선착순", 100, null, null, END));
        }

        @DisplayName("CPN-02 줄 길이 제한이 없으면 당첨자 수 × 10이다.")
        @Test
        void defaultsQueueCapacityToTenTimes() {
            CouponCampaign campaign = new CouponCampaign("선착순", 100, null, START, END);

            assertThat(campaign.getQueueCapacity()).isEqualTo(1_000);
        }

        @DisplayName("CPN-02 줄 길이 제한이 당첨자 수보다 작으면 거절한다.")
        @Test
        void rejectsQueueCapacitySmallerThanQuantity() {
            assertBadRequest(() -> new CouponCampaign("선착순", 100, 99, START, END));
        }
    }

    @DisplayName("발급 기간은 시작 시각을 포함하고 마감 시각을 제외한다.")
    @Test
    void openPeriodIncludesStartExcludesEnd() {
        CouponCampaign campaign = new CouponCampaign("선착순", 100, null, START, END);

        assertThat(campaign.isOpenAt(START.minusNanos(1))).isFalse();
        assertThat(campaign.isOpenAt(START)).isTrue();
        assertThat(campaign.isOpenAt(END.minusNanos(1))).isTrue();
        assertThat(campaign.isOpenAt(END)).isFalse();
    }

    private static void assertBadRequest(Runnable creation) {
        assertThatThrownBy(creation::run)
            .isInstanceOfSatisfying(CoreException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }
}
