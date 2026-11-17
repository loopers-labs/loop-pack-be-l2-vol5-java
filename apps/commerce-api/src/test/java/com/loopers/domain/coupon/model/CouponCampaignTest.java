package com.loopers.domain.coupon.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CouponCampaignTest {
    private static final Instant START = Instant.parse("2026-11-13T01:00:00Z");
    private static final Instant END = Instant.parse("2026-11-13T02:00:00Z");
    private static final CouponBenefit BENEFIT = new CouponBenefit("5,000원 할인권", 5_000L);

    @Nested
    @DisplayName("캠페인 준비와 복원")
    class Preparation {
        @Test
        @DisplayName("Unicode 이름 경계를 허용하고 준비 슬롯 수가 다르면 활성화하지 않는다")
        void activatesOnlyWithPreparedQuota() {
            // arrange
            CouponCampaign campaign = CouponCampaign.create("  " + "🎟".repeat(100) + "  ", 3, BENEFIT, START, END);

            // act / assert
            assertThatThrownBy(() -> campaign.activate(2))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_SLOTS_NOT_READY);
            assertThat(campaign.isActive()).isFalse();
            assertThat(campaign.getIssuedCount()).isZero();

            campaign.activate(3);
            assertThat(campaign.isActive()).isTrue();
            assertThat(campaign.getName()).isEqualTo("🎟".repeat(100));
        }

        @Test
        @DisplayName("잘못된 이름·총량·기간과 발급 수가 한도를 넘는 복원을 거절한다")
        void rejectsInvalidCampaignState() {
            // act / assert
            assertThatThrownBy(() -> CouponCampaign.create(" ", 1, BENEFIT, START, END))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_INVALID_CAMPAIGN_NAME);
            assertThatThrownBy(() -> CouponCampaign.create("행사", 0, BENEFIT, START, END))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_INVALID_QUOTA);
            assertThatThrownBy(() -> CouponCampaign.create("행사", 1, BENEFIT, END, START))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_INVALID_PERIOD);
            assertThatThrownBy(() -> CouponCampaign.restore(1L, "행사", true, 1, 2, BENEFIT, START, END))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_INVALID_QUOTA);
        }
    }

    @Nested
    @DisplayName("최종 발급 판단")
    class Issuance {
        @Test
        @DisplayName("주입받은 DB 시각으로 시작 직전·시작·종료 직전·종료 경계를 판단한다")
        void enforcesInclusiveStartAndExclusiveEnd() {
            // arrange
            CouponCampaign campaign = CouponCampaign.restore(1L, "행사", true, 3, 0, BENEFIT, START, END);

            // act / assert
            assertThatThrownBy(() -> campaign.recordIssue(START.minus(1, ChronoUnit.MICROS)))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_CAMPAIGN_NOT_STARTED);
            assertThat(campaign.getIssuedCount()).isZero();

            campaign.recordIssue(START);
            campaign.recordIssue(END.minus(1, ChronoUnit.MICROS));
            assertThat(campaign.getIssuedCount()).isEqualTo(2);

            assertThatThrownBy(() -> campaign.recordIssue(END))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_CAMPAIGN_ENDED);
            assertThat(campaign.getIssuedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("마지막 한 장을 발급한 뒤 추가 시도를 거절하며 int 범위를 넘기지 않는다")
        void preservesQuotaAtIntegerLimit() {
            // arrange
            CouponCampaign campaign = CouponCampaign.restore(
                    1L, "행사", true, Integer.MAX_VALUE, Integer.MAX_VALUE - 1, BENEFIT, START, END);

            // act
            campaign.recordIssue(START);

            // assert
            assertThatThrownBy(() -> campaign.recordIssue(START))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_SOLD_OUT);
            assertThat(campaign.getIssuedCount()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("비활성 발급은 상태를 유지하고 재활성도 누적 발급 수를 초기화하지 않는다")
        void preservesIssuedCountAcrossActivation() {
            // arrange
            CouponCampaign campaign = CouponCampaign.restore(1L, "행사", true, 3, 1, BENEFIT, START, END);

            // act
            campaign.deactivate();

            // assert
            assertThatThrownBy(() -> campaign.recordIssue(START))
                    .isInstanceOf(DomainException.class).extracting("errorCode")
                    .isEqualTo(DomainErrorCode.COUPON_CAMPAIGN_INACTIVE);
            assertThat(campaign.getIssuedCount()).isEqualTo(1);
            campaign.activate(3);
            campaign.recordIssue(START);
            assertThat(campaign.getIssuedCount()).isEqualTo(2);
        }
    }
}
