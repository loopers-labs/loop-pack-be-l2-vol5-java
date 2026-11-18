package com.loopers.domain.coupon.enums;

/** DB 에 남기는 접수 상태. SOLD_OUT 은 읽을 때 플래그로 계산하고, PENDING_CONFIRM 은 Redis 에만 있다 (8주차 D-153) */
public enum IssueRequestStatus {
    WAITING,
    RESERVE,
    ISSUED,
    FAILED
}
