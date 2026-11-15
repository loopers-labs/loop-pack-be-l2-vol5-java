package com.loopers.domain.coupon;

public record CouponCampaign(
        long campaignId,
        String name,
        boolean active,
        long totalQuota,
        long issuedCount,
        String benefitDescription) {
}
