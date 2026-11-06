package com.loopers.infrastructure.outbox;

import com.loopers.application.outbox.OutboxPublishException;

/** 브로커에 닿지 못했거나 제한 시간 안에 확인받지 못했다. 회로 차단이 실패로 세는 유일한 전송 예외다. */
public class BrokerUnavailableException extends OutboxPublishException {

    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
