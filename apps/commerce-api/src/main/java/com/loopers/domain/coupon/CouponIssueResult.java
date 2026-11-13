package com.loopers.domain.coupon;

/**
 * 발급 요청의 결과.
 *
 * @param couponId 보유 쿠폰 한 건의 ID
 * @param created  이번 요청이 새로 발급했으면 true, 이미 받은 쿠폰을 돌려주는 것이면 false
 */
public record CouponIssueResult(Long couponId, boolean created) {
}
