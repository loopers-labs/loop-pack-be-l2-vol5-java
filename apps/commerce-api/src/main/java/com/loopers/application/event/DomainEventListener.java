package com.loopers.application.event;

import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DomainEventListener {
    private final OutboxRecorder recorder;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void beforeCommit(DomainEvent event) {
        // Serialization and persistence errors must roll back the originating business transaction.
        recorder.record(event);
    }
}
