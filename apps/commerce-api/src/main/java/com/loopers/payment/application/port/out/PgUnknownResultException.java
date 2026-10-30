package com.loopers.payment.application.port.out;

/**
 * 보냈을 수 있으나 결과를 모른다. 다시 보내지 않고 조회로만 확인한다 (ADR-W5-01).
 */
public class PgUnknownResultException extends RuntimeException {

    public PgUnknownResultException(String message) {
        super(message);
    }

    public PgUnknownResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
