package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;

/**
 * 대기열 접수 API의 결과.
 */
public sealed interface CouponIssueQueueResult {

    /** 접수했거나 진행 중인 작업이다. 처리 시작 시점은 약속하지 않는다. */
    record Accepted(CouponIssueRequest request) implements CouponIssueQueueResult {
    }

    /** 결과가 확정된 기존 작업이다. */
    record Completed(CouponIssueRequest request) implements CouponIssueQueueResult {
    }

    /** 작업 이력은 없지만 이미 발급된 쿠폰이 있어 작업을 만들지 않았다. */
    record CouponAlreadyIssued(CouponIssueInfo coupon) implements CouponIssueQueueResult {
    }
}
