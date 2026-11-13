package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaign;

/** 캠페인의 지금 상태. 준비 코드와 테스트가 확인할 때 쓴다. */
public record CouponCampaignInfo(
    Long campaignId,
    String name,
    boolean active,
    int totalQuota,
    int issuedCount,
    String benefitDescription,
    long discountAmount
) {
    public static CouponCampaignInfo from(CouponCampaign campaign) {
        return new CouponCampaignInfo(
            campaign.getId(),
            campaign.getName(),
            campaign.isActive(),
            campaign.getTotalQuota(),
            campaign.getIssuedCount(),
            campaign.getBenefit().getDescription(),
            campaign.getBenefit().getDiscountAmount()
        );
    }
}
