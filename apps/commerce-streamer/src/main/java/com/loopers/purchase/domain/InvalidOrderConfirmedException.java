package com.loopers.purchase.domain;

/**
 * 계약(OrderConfirmed v1)에 맞지 않는 이벤트 — 다시 시도해도 같다. 재시도 없이 DLT로 보낸다 (ADR-W6-06).
 */
public class InvalidOrderConfirmedException extends RuntimeException {

    public InvalidOrderConfirmedException(String message, Throwable cause) {
        super(message, cause);
    }
}
