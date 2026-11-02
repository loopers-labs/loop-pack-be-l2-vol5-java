package com.loopers.outbox.application.port.in;

import com.loopers.outbox.domain.OutboxMessage;

/**
 * 업무 트랜잭션 안에서 발행 기록을 남긴다. 트랜잭션 밖에서 부르면 예외다 — commit과 함께 남아야 의미가 있다.
 */
public interface OutboxAppendUseCase {

    void append(OutboxMessage message);
}
