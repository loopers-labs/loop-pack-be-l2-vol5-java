package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueJob;

/** 묶음 저장에서 작업 하나의 결과. CREATED·EXISTING이면 쿠폰이, REJECTED면 확정 거절 이유가 있다. */
record CouponIssueOutcome(CouponIssueJob job, Kind kind, CouponInfo coupon, String reason) {
    enum Kind { CREATED, EXISTING, REJECTED }

    static CouponIssueOutcome created(CouponIssueJob job, CouponInfo coupon) { return new CouponIssueOutcome(job, Kind.CREATED, coupon, null); }
    static CouponIssueOutcome existing(CouponIssueJob job, CouponInfo coupon) { return new CouponIssueOutcome(job, Kind.EXISTING, coupon, null); }
    static CouponIssueOutcome rejected(CouponIssueJob job, String reason) { return new CouponIssueOutcome(job, Kind.REJECTED, null, reason); }
}
