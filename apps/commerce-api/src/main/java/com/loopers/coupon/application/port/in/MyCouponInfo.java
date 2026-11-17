package com.loopers.coupon.application.port.in;

import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.DiscountType;
import com.loopers.coupon.domain.UserCoupon;
import com.loopers.coupon.domain.UserCouponStatus;

import java.time.ZonedDateTime;

/**
 * 내 쿠폰 한 장: 발급 결과(user_coupon)와 할인 규칙(coupon)을 합친 것.
 */
public record MyCouponInfo(
    Long id,
    Long couponId,
    Long campaignId,
    String name,
    DiscountType discountType,
    long discountValue,
    Long maxDiscountAmount,
    long minOrderAmount,
    UserCouponStatus status,
    ZonedDateTime issuedAt,
    ZonedDateTime expiresAt
) {
    public static MyCouponInfo of(UserCoupon userCoupon, Coupon coupon) {
        return new MyCouponInfo(
            userCoupon.getId(),
            coupon.getId(),
            userCoupon.getCampaignId(),
            coupon.getName(),
            coupon.getDiscountType(),
            coupon.getDiscountValue(),
            coupon.getMaxDiscountAmount(),
            coupon.getMinOrderAmount(),
            userCoupon.getStatus(),
            userCoupon.getIssuedAt(),
            userCoupon.getExpiresAt()
        );
    }
}
