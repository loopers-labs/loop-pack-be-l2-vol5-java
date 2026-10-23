package com.loopers.infrastructure.payment.client;

/** 조회 1회의 실패. 모두 회로 실패로 세고, timeout · 연결 실패 · 5xx 만 재조회함 */
class LookupFailure extends RuntimeException {

    private final boolean retryable;

    private LookupFailure(String message, boolean retryable) {
        super(message, null, false, false);
        this.retryable = retryable;
    }

    static LookupFailure retryable(String message) {
        return new LookupFailure(message, true);
    }

    /** 같은 응답이 반복될 것이라 재조회하지 않음 */
    static LookupFailure contractViolation(String message) {
        return new LookupFailure(message, false);
    }

    boolean isRetryable() {
        return retryable;
    }
}
