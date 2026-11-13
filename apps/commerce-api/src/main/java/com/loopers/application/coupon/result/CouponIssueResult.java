package com.loopers.application.coupon.result;

import com.loopers.domain.coupon.model.Coupon;
import java.time.Instant;

public record CouponIssueResult(long couponId, long campaignId, Instant issuedAt,
                                String benefitDescription, long discountAmount, boolean created) {
    public static CouponIssueResult from(Coupon coupon, boolean created) {
        return new CouponIssueResult(coupon.getId(), coupon.getCampaignId(), coupon.getIssuedAt(),
            coupon.getBenefit().description(), coupon.getBenefit().discountAmount(), created);
    }
}
