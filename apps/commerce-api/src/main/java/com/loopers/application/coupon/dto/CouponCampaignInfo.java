package com.loopers.application.coupon.dto;

import com.loopers.domain.coupon.CouponCampaign;

public record CouponCampaignInfo(Long id, String name, long discountAmount, int totalQuota, int issuedCount, boolean active) {

    public static CouponCampaignInfo from(CouponCampaign campaign) {
        return new CouponCampaignInfo(
            campaign.getId(),
            campaign.getName(),
            campaign.getDiscountAmount().amount(),
            campaign.getTotalQuota(),
            campaign.getIssuedCount(),
            campaign.isActive()
        );
    }
}
