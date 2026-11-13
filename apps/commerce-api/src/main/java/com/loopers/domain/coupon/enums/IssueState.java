package com.loopers.domain.coupon.enums;

/** 사용자에게 보이는 접수 상태. PENDING_CONFIRM 은 Kafka 확인 전이라 응답으로는 나가지 않는다 (8주차 D-133, D-144) */
public enum IssueState {
    PENDING_CONFIRM,
    WAITING,
    RESERVE,
    ISSUED,
    SOLD_OUT,
    FAILED
}
