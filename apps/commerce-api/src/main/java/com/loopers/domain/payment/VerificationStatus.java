package com.loopers.domain.payment;

/** DB 에 마지막으로 반영한 확인 결과. 요청 시작 순서나 PG 사건 시각 순서의 최신성을 뜻하지 않는다(설계 3.6). */
public enum VerificationStatus {
    NOT_CHECKED,
    VERIFIED,
    UNAVAILABLE,
    CONFLICT
}
