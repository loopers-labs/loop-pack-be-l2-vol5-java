package com.loopers.interfaces.api.coupon;

import com.loopers.domain.coupon.Coupon;

import java.time.Instant;
import java.util.List;

public class CouponV1Dto {

    public record CouponResponse(Long couponId, Long campaignId, long discountAmount, Instant issuedAt) {
        public static CouponResponse from(Coupon coupon) {
            return new CouponResponse(coupon.id(), coupon.campaignId(), coupon.discountAmount().value(),
                coupon.issuedAt());
        }

        public static List<CouponResponse> listOf(List<Coupon> coupons) {
            return coupons.stream().map(CouponResponse::from).toList();
        }
    }
}
