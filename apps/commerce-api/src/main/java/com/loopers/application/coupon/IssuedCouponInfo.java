package com.loopers.application.coupon;

import com.loopers.domain.coupon.IssuedCoupon;

import java.time.ZonedDateTime;

/** 발급된 쿠폰 한 장. 혜택은 발급 순간 복사해 둔 값이다(docs/week8/decisions.md D-15, INV-806). */
public record IssuedCouponInfo(
    Long couponId,
    Long campaignId,
    Long userId,
    String benefitDescription,
    long discountAmount,
    ZonedDateTime issuedAt
) {
    public static IssuedCouponInfo from(IssuedCoupon coupon) {
        return new IssuedCouponInfo(
            coupon.getId(),
            coupon.getCampaignId(),
            coupon.getUserId(),
            coupon.getBenefit().getDescription(),
            coupon.getBenefit().getDiscountAmount(),
            coupon.getIssuedAt()
        );
    }
}
