package com.loopers.interfaces.consumer;

/** OrderConfirmed record 를 계약대로 읽을 수 없다 (형식·eventType·schemaVersion·key 불일치). 재시도해도 같은 결과라 소비를 멈춰 드러낸다. */
public class InvalidOrderConfirmedMessageException extends RuntimeException {

    public InvalidOrderConfirmedMessageException(String message) {
        super(message);
    }

    public InvalidOrderConfirmedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
