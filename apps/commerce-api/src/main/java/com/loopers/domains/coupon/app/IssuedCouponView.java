package com.loopers.domains.coupon.app;

import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Instant;

public record IssuedCouponView(long couponId, long campaignId, long discountAmount, Instant issuedAt) {
    static IssuedCouponView from(IssuedCoupon coupon) {
        return new IssuedCouponView(coupon.id(), coupon.campaignId(), coupon.discountAmount(), coupon.issuedAt());
    }
}
