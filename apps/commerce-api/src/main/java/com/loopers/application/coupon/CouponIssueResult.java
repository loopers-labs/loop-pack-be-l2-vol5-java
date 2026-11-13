package com.loopers.application.coupon;

/**
 * DB 발급 결과. 처음 발급(201)과 이미 받은 사용자(200, 같은 couponId)를 나눈다(docs/week8/decisions.md D-9).
 * 품절·비활성·없는 캠페인은 결과가 아니라 예외(CoreException)다.
 */
public record CouponIssueResult(Outcome outcome, IssuedCouponInfo coupon) {

    public enum Outcome {
        /** 이번 실행이 쿠폰을 새로 만들었다. 발급 수가 1 올랐다. */
        CREATED,
        /** 이미 받은 쿠폰을 돌려준다. 발급 수는 그대로다. */
        EXISTING
    }

    public static CouponIssueResult created(IssuedCouponInfo coupon) {
        return new CouponIssueResult(Outcome.CREATED, coupon);
    }

    public static CouponIssueResult existing(IssuedCouponInfo coupon) {
        return new CouponIssueResult(Outcome.EXISTING, coupon);
    }
}
