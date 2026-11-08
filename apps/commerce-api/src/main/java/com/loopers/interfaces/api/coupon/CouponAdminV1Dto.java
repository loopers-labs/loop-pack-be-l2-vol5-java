package com.loopers.interfaces.api.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;

public class CouponAdminV1Dto {

    public record OpenRequest(String name, Long discountAmount, Integer totalQuota, Boolean active) {
        public OpenRequest {
            CampaignName.of(name);
            if (discountAmount == null) {
                throw new IllegalArgumentException("discountAmount 는 필수입니다.");
            }
            DiscountAmount.of(discountAmount);
            if (totalQuota == null) {
                throw new IllegalArgumentException("totalQuota 는 필수입니다.");
            }
            IssueQuota.of(totalQuota);
            if (active == null) {
                throw new IllegalArgumentException("active 는 필수입니다.");
            }
        }

        public CampaignName toName() {
            return CampaignName.of(name);
        }

        public DiscountAmount toDiscountAmount() {
            return DiscountAmount.of(discountAmount);
        }

        public IssueQuota toTotalQuota() {
            return IssueQuota.of(totalQuota);
        }
    }

    public record CampaignResponse(Long campaignId, String name, long discountAmount, int totalQuota, int issuedCount,
                                   boolean active) {
        public static CampaignResponse from(CouponCampaign campaign) {
            return new CampaignResponse(campaign.getId(), campaign.getName().value(),
                campaign.getDiscountAmount().value(), campaign.getTotalQuota().value(), campaign.getIssuedCount(),
                campaign.isActive());
        }
    }
}
