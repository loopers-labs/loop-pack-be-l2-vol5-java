package com.loopers.payment.application.port.out;

/**
 * 조회하지 못했다. 결제 상태를 바꾸지 않는다 — 실패로 확정하지 않는다 (plan.md 6장).
 */
public class PgLookupUnavailableException extends RuntimeException {

    public PgLookupUnavailableException(String message) {
        super(message);
    }

    public PgLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
