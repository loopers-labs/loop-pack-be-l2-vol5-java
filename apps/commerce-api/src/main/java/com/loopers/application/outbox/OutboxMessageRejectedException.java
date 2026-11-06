package com.loopers.application.outbox;

/** 이 메시지는 다시 보내도 거절된다(크기 초과·직렬화·권한 등). relay 는 재시도하지 않고 격리한다. */
public class OutboxMessageRejectedException extends OutboxPublishException {

    public OutboxMessageRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
