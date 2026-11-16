package com.loopers.domain.coupon;

/** SOLD_OUT 은 저장하지 않는 표현 상태다. */
public enum CouponWaitingState {
    WAITING,
    READY,
    PROCESSING,
    ISSUED,
    EXPIRED,
    FAILED,
    SOLD_OUT
}
