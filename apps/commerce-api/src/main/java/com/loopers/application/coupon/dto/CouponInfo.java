package com.loopers.application.coupon.dto;

import com.loopers.domain.coupon.Coupon;

import java.time.ZonedDateTime;

public record CouponInfo(Long couponId, Long campaignId, long discountAmount, ZonedDateTime issuedAt) {

    public static CouponInfo from(Coupon coupon) {
        return new CouponInfo(coupon.getId(), coupon.getCampaignId(), coupon.getDiscountAmount().amount(), coupon.getIssuedAt());
    }
}
