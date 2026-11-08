package com.loopers.domain.coupon;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponCampaignTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private static CouponCampaign campaign(boolean active) {
        return CouponCampaign.restore(7L, CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(100), 3, active);
    }

    @DisplayName("열면 발급 수가 0 이다")
    @Test
    void opensWithZeroIssued() {
        CouponCampaign campaign = CouponCampaign.open(
            CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000), IssueQuota.of(100), true);

        assertThat(campaign.getIssuedCount()).isZero();
        assertThat(campaign.getName()).isEqualTo(CampaignName.of("블프 쿠폰"));
        assertThat(campaign.getDiscountAmount()).isEqualTo(DiscountAmount.of(5_000));
        assertThat(campaign.getTotalQuota()).isEqualTo(IssueQuota.of(100));
        assertThat(campaign.isActive()).isTrue();
    }

    @Nested
    @DisplayName("발급하면")
    class IssueTo {

        @DisplayName("캠페인의 할인 금액을 쿠폰에 옮겨 적는다")
        @Test
        void copiesBenefitIntoCoupon() {
            Coupon coupon = campaign(true).issueTo(42L, NOW);

            assertThat(coupon.id()).isNull();
            assertThat(coupon.campaignId()).isEqualTo(7L);
            assertThat(coupon.userId()).isEqualTo(42L);
            assertThat(coupon.discountAmount()).isEqualTo(DiscountAmount.of(5_000));
            assertThat(coupon.issuedAt()).isEqualTo(NOW);
        }

        @DisplayName("비활성 캠페인이면 거절한다")
        @Test
        void rejectsInactiveCampaign() {
            assertThatThrownBy(() -> campaign(false).issueTo(42L, NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.COUPON_CAMPAIGN_INACTIVE);
        }
    }

    @DisplayName("한도와 할인 금액은 0 이하일 수 없다")
    @Test
    void quotaAndDiscountMustBePositive() {
        assertThatThrownBy(() -> IssueQuota.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountAmount.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("캠페인 이름은 비어 있을 수 없다")
    @Test
    void nameMustNotBeBlank() {
        assertThatThrownBy(() -> CampaignName.of(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
