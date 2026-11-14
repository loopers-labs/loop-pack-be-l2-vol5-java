package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueInfo;

import java.time.Instant;

public final class CouponV1Dto {

    private CouponV1Dto() {}

    public record CouponResponse(Long couponId, Long campaignId, Instant issuedAt, String benefitDescription) {
        public static CouponResponse from(CouponIssueInfo info) {
            return new CouponResponse(info.couponId(), info.campaignId(), info.issuedAt(), info.benefitDescription());
        }
    }
}
