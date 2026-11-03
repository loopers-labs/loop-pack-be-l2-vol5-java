package com.loopers.application.event;

import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class DomainEventListener {
    private final DomainEventPublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(DomainEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException exception) {
            // The business transaction has committed. A delivery failure must not trigger a business retry.
            log.error("domain_event_dispatch_failed eventType={} eventId={} key={} errorType={}",
                event.eventType(), event.eventId(), event.key(), exception.getClass().getSimpleName());
        }
    }
}
