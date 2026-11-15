package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponModel;

import java.time.ZonedDateTime;

public record CouponInfo(Long couponId, Long campaignId, String benefitDescription, ZonedDateTime issuedAt) {
    public static CouponInfo from(CouponModel coupon) {
        return new CouponInfo(coupon.getId(), coupon.getCampaignId(), coupon.getBenefitDescription(), coupon.getIssuedAt());
    }
}
