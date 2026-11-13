package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.dto.CouponInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.coupon.enums.IssueState;

import java.time.ZonedDateTime;

public class CouponV1Dto {

    /** WAITING 은 상태만, RESERVE 는 현재 예비 위치, ISSUED 는 couponId 를 담는다 (8주차 D-149) */
    public record IssueResponse(Long campaignId, IssueState status, Integer reservePosition, Long couponId) {
        public static IssueResponse from(CouponIssueInfo info) {
            return new IssueResponse(info.campaignId(), info.state(), info.reservePosition(), info.couponId());
        }
    }

    public record CouponResponse(
        Long couponId,
        Long campaignId,
        DiscountType discountType,
        long discountValue,
        String description,
        ZonedDateTime issuedAt
    ) {
        public static CouponResponse from(CouponInfo info) {
            return new CouponResponse(info.couponId(), info.campaignId(), info.discountType(),
                info.discountValue(), info.description(), info.issuedAt());
        }
    }
}
