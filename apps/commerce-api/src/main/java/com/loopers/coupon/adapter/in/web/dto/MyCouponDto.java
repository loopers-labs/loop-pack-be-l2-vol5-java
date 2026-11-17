package com.loopers.coupon.adapter.in.web.dto;

import com.loopers.coupon.application.port.in.MyCouponInfo;
import com.loopers.coupon.domain.DiscountType;
import com.loopers.coupon.domain.UserCouponStatus;

import java.time.ZonedDateTime;

public class MyCouponDto {

    public record MyCouponResponse(
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
        public static MyCouponResponse from(MyCouponInfo info) {
            return new MyCouponResponse(info.id(), info.couponId(), info.campaignId(), info.name(), info.discountType(),
                info.discountValue(), info.maxDiscountAmount(), info.minOrderAmount(), info.status(), info.issuedAt(), info.expiresAt());
        }
    }
}
