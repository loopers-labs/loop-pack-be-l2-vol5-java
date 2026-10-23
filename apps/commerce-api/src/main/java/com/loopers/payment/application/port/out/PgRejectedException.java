package com.loopers.payment.application.port.out;

/**
 * PG가 거래를 만들지 않고 거절했다 (효과 0건). 실패로 확정해도 된다.
 */
public class PgRejectedException extends RuntimeException {

    public PgRejectedException(String message) {
        super(message);
    }

    public PgRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
