package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueResult;

/**
 * @param couponId 보유 쿠폰 한 건의 ID
 * @param created  이번 요청이 새로 발급했으면 true(201), 이미 받은 쿠폰을 돌려주는 것이면 false(200)
 */
public record CouponIssueInfo(Long couponId, boolean created) {
    public static CouponIssueInfo from(CouponIssueResult result) {
        return new CouponIssueInfo(result.couponId(), result.created());
    }
}
