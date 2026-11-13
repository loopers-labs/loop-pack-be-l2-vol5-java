package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.IssuedCouponInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class CouponV1Dto {

    /** 발급된 쿠폰 한 장. 혜택은 발급 순간 복사된 값이다(docs/week8/decisions.md D-15, INV-806). */
    public record CouponResponse(
        Long couponId,
        Long campaignId,
        String benefitDescription,
        long discountAmount,
        ZonedDateTime issuedAt
    ) {
        public static CouponResponse from(IssuedCouponInfo info) {
            return new CouponResponse(
                info.couponId(),
                info.campaignId(),
                info.benefitDescription(),
                info.discountAmount(),
                info.issuedAt()
            );
        }
    }

    public record CouponListResponse(List<CouponResponse> coupons) {
        public static CouponListResponse from(List<IssuedCouponInfo> infos) {
            return new CouponListResponse(infos.stream().map(CouponResponse::from).toList());
        }
    }
}
