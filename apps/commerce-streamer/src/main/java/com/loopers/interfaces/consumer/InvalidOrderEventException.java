package com.loopers.interfaces.consumer;

/** 계약(OrderConfirmed v1)을 지키지 않은 record. 건너뛰지 않고 소비를 멈추도록 호출자에게 던진다. */
public class InvalidOrderEventException extends RuntimeException {

    public InvalidOrderEventException(String message) {
        super(message);
    }

    public InvalidOrderEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
