package com.loopers.application.coupon;

/**
 * seed reset 전에 캠페인의 대기열 정보를 정리한 결과.
 */
public sealed interface CouponIssueQueueCleanup {

    /** 남은 실행이 없어 캠페인의 작업 정보와 중복 접수 정보를 지웠다. */
    record Cleared(int removedRequests) implements CouponIssueQueueCleanup {
    }

    /** 미완료 작업이나 실행 token이 남아 있어 아무것도 지우지 않았다. */
    record Blocked(String reason) implements CouponIssueQueueCleanup {
    }
}
