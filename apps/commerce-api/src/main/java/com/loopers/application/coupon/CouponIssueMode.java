package com.loopers.application.coupon;

/** 발급 경로. DB_ONLY·REDIS_SYNC는 DB commit 뒤 결과를 응답하고, QUEUE는 접수만 응답한다. */
public enum CouponIssueMode {
    DB_ONLY,
    REDIS_SYNC,
    /** Redis 판단 + 작업 큐 등록 뒤 202로 답하고, DB 저장은 Worker가 한다. */
    QUEUE
}
