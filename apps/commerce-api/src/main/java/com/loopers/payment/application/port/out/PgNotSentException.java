package com.loopers.payment.application.port.out;

/**
 * 요청이 나가지 않은 게 확실하다 (회로 OPEN, 연결 실패). 실패로 닫아도 이중 결제가 생기지 않는다 (ADR-W5-03).
 */
public class PgNotSentException extends RuntimeException {

    public PgNotSentException(String message) {
        super(message);
    }

    public PgNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
