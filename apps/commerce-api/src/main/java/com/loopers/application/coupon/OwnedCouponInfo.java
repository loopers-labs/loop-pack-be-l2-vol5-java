package com.loopers.application.coupon;

import com.loopers.domain.coupon.OwnedCoupon;

import java.time.Instant;

public record OwnedCouponInfo(long couponId, long campaignId, String benefitDescription, Instant issuedAt) {
    static OwnedCouponInfo from(OwnedCoupon coupon) {
        return new OwnedCouponInfo(coupon.getId(), coupon.getReservation().getCampaign().getId(),
            coupon.getBenefitDescription(), coupon.getIssuedAt());
    }
}
