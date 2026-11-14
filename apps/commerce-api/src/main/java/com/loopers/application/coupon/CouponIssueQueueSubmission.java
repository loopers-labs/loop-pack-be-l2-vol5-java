package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;

/**
 * 대기열 접수 결과.
 */
public sealed interface CouponIssueQueueSubmission {

    /** 새 작업을 저장하고 대기열 끝에 추가했다. */
    record Created(CouponIssueRequest request) implements CouponIssueQueueSubmission {
    }

    /** 같은 사용자와 캠페인의 작업이 이미 있어 새 작업을 만들지 않았다. */
    record Existing(CouponIssueRequest request) implements CouponIssueQueueSubmission {
    }

    /** 미완료 작업이 한도에 차서 새 작업을 만들지 않았다. */
    record Full() implements CouponIssueQueueSubmission {
    }
}
