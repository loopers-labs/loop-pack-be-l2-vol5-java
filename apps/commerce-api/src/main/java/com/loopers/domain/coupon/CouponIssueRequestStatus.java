package com.loopers.domain.coupon;

/**
 * 대기열에 접수한 발급 작업의 상태. 상태 전이는 Redis Lua가 원자적으로 처리하고, 이 enum은 각 상태의 의미를 정한다.
 */
public enum CouponIssueRequestStatus {
    WAITING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    /**
     * 실행이나 수동 복구를 기다리는 상태이며 접수 한도에 포함된다.
     */
    public boolean isUnfinished() {
        return this == WAITING || this == PROCESSING || this == UNKNOWN;
    }

    /**
     * 결과가 확정된 상태이며 접수 한도에서 제외된다.
     */
    public boolean isCompleted() {
        return this == SUCCEEDED || this == FAILED;
    }
}
