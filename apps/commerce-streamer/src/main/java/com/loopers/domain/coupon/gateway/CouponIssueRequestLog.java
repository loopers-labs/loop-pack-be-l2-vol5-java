package com.loopers.domain.coupon.gateway;

/** 접수 기록 topic 의 적재 진행 (8주차 D-140, D-174) */
public interface CouponIssueRequestLog {

    /** consumer group 이 topic 의 모든 partition 끝까지 적재했는가. 확인할 수 없으면 false */
    boolean caughtUp();
}
