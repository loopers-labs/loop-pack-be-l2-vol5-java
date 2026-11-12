package com.loopers.domain.coupon;

/**
 * 대기 항목 상태(설계 §12.8·12.11). 저장되는 상태는 SOLD_OUT 을 뺀 여섯 개다.
 * SOLD_OUT 은 품절 표시가 있을 때 WAITING/READY 를 조회·응답에서 바꿔 보여 주는 표현 상태다.
 */
public enum CouponWaitingState {
    WAITING,
    READY,
    PROCESSING,
    ISSUED,
    EXPIRED,
    FAILED,
    SOLD_OUT
}
