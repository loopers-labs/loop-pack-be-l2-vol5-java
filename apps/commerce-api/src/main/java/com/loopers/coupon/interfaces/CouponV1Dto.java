package com.loopers.coupon.interfaces;

import com.loopers.coupon.domain.Coupon;

import java.time.ZonedDateTime;

public final class CouponV1Dto {

    private CouponV1Dto() {
    }

    public record CouponResponse(
        Long id,
        ZonedDateTime issuedAt,
        Long userId,
        Long campaignId,
        String benefit
    ) {
        public static CouponResponse from(Coupon coupon) {
            return new CouponResponse(
                coupon.getId(),
                coupon.getIssuedAt(),
                coupon.getUserId(),
                coupon.getCampaignId(),
                coupon.getBenefit()
            );
        }
    }
}
