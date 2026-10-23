package com.loopers.payment.adapter.out.pg;

/**
 * 조회가 일시적으로 실패했다 (읽기 timeout, 연결 실패, 5xx, 응답 해석 불가). 재시도하고 회로 실패로 센다 (plan.md 6장).
 * 어댑터 밖으로는 나가지 않는다 — 끝내 실패하면 PgLookupUnavailableException으로 바꾼다.
 */
class PgLookupRetryableException extends RuntimeException {

    PgLookupRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
