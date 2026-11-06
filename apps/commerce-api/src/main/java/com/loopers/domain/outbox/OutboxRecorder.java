package com.loopers.domain.outbox;

import com.loopers.domain.event.DomainEvent;

/** Durable recording participates in the caller's local transaction; it does not deliver to a broker. */
public interface OutboxRecorder {
    void record(DomainEvent event);
}
