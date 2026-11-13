package com.loopers.application.coupon.dto;

import com.loopers.domain.coupon.entity.Coupon;
import com.loopers.domain.coupon.enums.DiscountType;

import java.time.ZonedDateTime;

public record CouponInfo(
    Long couponId,
    Long campaignId,
    DiscountType discountType,
    long discountValue,
    String description,
    ZonedDateTime issuedAt
) {
    public static CouponInfo from(Coupon coupon) {
        return new CouponInfo(coupon.getId(), coupon.getCampaignId(), coupon.getDiscountType(),
            coupon.getDiscountValue(), coupon.getDescription(), coupon.getIssuedAt());
    }
}
