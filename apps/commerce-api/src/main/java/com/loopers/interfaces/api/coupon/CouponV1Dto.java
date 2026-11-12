package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class CouponV1Dto {

    public record CouponResponse(Long couponId, Long campaignId, String benefitDescription, ZonedDateTime issuedAt) {
        public static CouponResponse from(CouponInfo info) {
            return new CouponResponse(info.couponId(), info.campaignId(), info.benefitDescription(), info.issuedAt());
        }
    }

    public record CouponListResponse(List<CouponResponse> coupons) {
        public static CouponListResponse from(List<CouponInfo> infos) {
            return new CouponListResponse(infos.stream().map(CouponResponse::from).toList());
        }
    }
}
