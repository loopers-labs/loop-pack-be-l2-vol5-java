package com.loopers.application.coupon.result;

import com.loopers.domain.coupon.model.CouponCampaign;

public record CouponCampaignResult(long campaignId, boolean active, int totalQuota,
                                   int issuedCount, long preparedSlotCount) {
    public static CouponCampaignResult from(CouponCampaign campaign, long preparedSlotCount) {
        return new CouponCampaignResult(campaign.getId(), campaign.isActive(), campaign.getTotalQuota(),
            campaign.getIssuedCount(), preparedSlotCount);
    }
}
