package com.loopers.application.coupon.admission;

public enum AcquireOutcome {
    ACQUIRED,
    FULL,
    CLOSED,
    /** 그사이 couponId 가 기록돼 더 기다릴 필요가 없다. */
    NOT_PENDING
}
