package com.loopers.support.event;

import com.loopers.application.event.DomainEventListener;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Test-only preservation of the W6 direct delivery boundary from revision dd86f49.
 * Never scanned by production: W6 tests explicitly replace the common listener with this fixture.
 */
public class W6DomainEventListener extends DomainEventListener {
    private static final Logger log = LoggerFactory.getLogger(W6DomainEventListener.class);
    private final DomainEventPublisher publisher;

    public W6DomainEventListener(DomainEventPublisher publisher) {
        super(null);
        this.publisher = publisher;
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void beforeCommit(DomainEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException exception) {
            // Preserve W6 semantics: delivery failure cannot roll back an already committed order.
            log.error("domain_event_dispatch_failed eventType={} eventId={} key={} errorType={}",
                event.eventType(), event.eventId(), event.key(), exception.getClass().getSimpleName());
        }
    }
}
