package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;

/**
 * 발급 POST 의 결과(설계 §12.11). 쿠폰 결과(신규 201·기존 200) 또는 같은 권한이 진행 중인 PROCESSING 대기 상태(202) 중 정확히 하나다.
 * 둘 다 있거나 둘 다 없으면 내부 불변식 오류다.
 */
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
