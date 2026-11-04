package com.loopers.application.outbox;

/** 전송에 실패했거나 브로커의 확인을 받지 못했다. 메시지에는 짧은 원인만 담고 payload 는 넣지 않는다. */
public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
