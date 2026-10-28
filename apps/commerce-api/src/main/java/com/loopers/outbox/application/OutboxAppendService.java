package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OutboxAppendUseCase;
import com.loopers.outbox.application.port.out.EventOutboxPort;
import com.loopers.outbox.domain.OutboxAppendedEvent;
import com.loopers.outbox.domain.OutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행 기록을 업무 트랜잭션 안에서 남기고, commit 뒤 보낼 수 있도록 프로세스 안 이벤트를 낸다 (ADR-W6-02·03).
 * MANDATORY — 트랜잭션 밖에서 부르면 IllegalTransactionStateException. 확정은 항상 트랜잭션 안에서 일어나야 하고,
 * 밖에서 낸 이벤트는 AFTER_COMMIT listener가 조용히 무시하므로(fallbackExecution=false) 여기서 먼저 막는다.
 */
@RequiredArgsConstructor
@Service
public class OutboxAppendService implements OutboxAppendUseCase {

    private final EventOutboxPort eventOutboxPort;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void append(OutboxMessage message) {
        String payload = eventOutboxPort.append(message);
        eventPublisher.publishEvent(new OutboxAppendedEvent(message.eventId(), message.topic(), message.key(), payload));
    }
}
