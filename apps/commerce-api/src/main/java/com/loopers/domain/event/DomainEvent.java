package com.loopers.domain.event;

/**
 * Explicit opt-in for immutable facts intended for external publication after their transaction commits.
 * Ordinary local events need not implement this contract. Transport destinations belong to infrastructure.
 */
public interface DomainEvent {
    String eventType();

    String eventId();

    String key();
}
