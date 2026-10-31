package com.loopers.interfaces.consumer;

/** 메시지 자체가 계약에 맞지 않아 다시 읽어도 처리할 수 없는 경우. 재시도하지 않고 DLQ로 보낸다. */
public class InvalidOrderConfirmedMessageException extends IllegalArgumentException {
    public InvalidOrderConfirmedMessageException(IllegalArgumentException cause) {
        super(cause.getMessage(), cause);
    }
}
