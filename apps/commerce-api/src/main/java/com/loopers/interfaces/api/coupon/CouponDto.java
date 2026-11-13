package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.dto.CouponInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo;

import java.time.ZonedDateTime;

public class CouponDto {

    /** PENDING 이면 couponId 가 없어 응답에서 키가 빠진다. */
    public record IssueResponse(String status, Long couponId) {
        public static IssueResponse from(CouponIssueInfo info) {
            return new IssueResponse(info.status().name(), info.couponId());
        }
    }

    public record CouponResponse(Long couponId, Long campaignId, long discountAmount, ZonedDateTime issuedAt) {
        public static CouponResponse from(CouponInfo info) {
            return new CouponResponse(info.couponId(), info.campaignId(), info.discountAmount(), info.issuedAt());
        }
    }
}
