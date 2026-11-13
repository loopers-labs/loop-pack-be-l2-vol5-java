package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;

/** 쿠폰 결과(201·200) 또는 진행 중 대기 상태(202) 중 정확히 하나다. */
public record CouponIssueOutcome(CouponIssueResult result, CouponWaitingSnapshot processing) {

    public CouponIssueOutcome {
        if ((result == null) == (processing == null)) {
            throw new IllegalStateException("발급 결과는 쿠폰 결과와 진행 중 상태 중 정확히 하나여야 합니다.");
        }
        if (processing != null && processing.status() != CouponWaitingState.PROCESSING) {
            throw new IllegalStateException("진행 중 결과의 상태가 PROCESSING 이 아닙니다. [status = " + processing.status() + "]");
        }
    }

    static CouponIssueOutcome issued(CouponIssueResult result) {
        return new CouponIssueOutcome(result, null);
    }

    static CouponIssueOutcome inProgress(CouponWaitingSnapshot snapshot) {
        return new CouponIssueOutcome(null, snapshot);
    }
}
