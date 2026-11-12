package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;

/**
 * 처리 결과 기록 결과. 실행 token과 현재 상태가 맞을 때만 기록한다.
 */
public sealed interface CouponIssueQueueRecord {

    /** 이번 요청으로 결과를 기록했다. */
    record Applied(CouponIssueRequest request) implements CouponIssueQueueRecord {
    }

    /** 같은 실행이 같은 결과를 이미 기록했다. 응답이 유실된 기록을 다시 보낸 경우다. */
    record AlreadyApplied(CouponIssueRequest request) implements CouponIssueQueueRecord {
    }

    /** 실행 token이나 현재 상태가 맞지 않아 바꾸지 않았다. 현재 작업을 함께 돌려준다. */
    record Rejected(CouponIssueRequest request) implements CouponIssueQueueRecord {
    }
}
