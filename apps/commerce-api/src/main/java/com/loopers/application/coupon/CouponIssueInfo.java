package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;

import java.time.Instant;

public record CouponIssueInfo(Long couponId, Long campaignId, Instant issuedAt, String benefitDescription) {

    public static CouponIssueInfo from(Coupon coupon, CouponCampaign campaign) {
        return new CouponIssueInfo(coupon.getId(), coupon.getCampaignId(), coupon.getIssuedAt(),
            campaign.getBenefitDescription());
    }
}
