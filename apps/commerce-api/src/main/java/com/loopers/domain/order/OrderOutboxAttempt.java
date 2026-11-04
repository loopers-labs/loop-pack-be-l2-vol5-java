package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 한 번의 Outbox 전송 시도 snapshot(설계 W7.3-2). 시도 시작 TX 가 commit 한 누적 attemptNo·cycleAttemptCount 와 저장 원문을 담는다.
 * Entity·Future 를 담지 않는다. 시도 번호는 처리 시도 시작 수이며 broker 수신 횟수가 아니다.
 */
public record OrderOutboxAttempt(long outboxId, long attemptNo, int cycleAttemptCount, OrderOutboxStoredMessage message) {

    public OrderOutboxAttempt {
        if (outboxId < 1L || attemptNo < 1L || cycleAttemptCount < 1 || cycleAttemptCount > attemptNo || message == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "Outbox 시도의 ID·시도 횟수·메시지가 올바르지 않습니다.");
        }
    }
}
