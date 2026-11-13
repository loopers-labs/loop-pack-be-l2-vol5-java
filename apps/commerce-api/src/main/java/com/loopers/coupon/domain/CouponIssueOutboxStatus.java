package com.loopers.coupon.domain;

/**
 * 당첨 기록의 전달 상태 (W7 OrderOutbox와 같은 전이: PENDING → SENDING → PUBLISHED | 실패 PENDING · 3번째 실패 BLOCKED).
 */
public enum CouponIssueOutboxStatus {
    PENDING,
    SENDING,
    PUBLISHED,
    BLOCKED
}
