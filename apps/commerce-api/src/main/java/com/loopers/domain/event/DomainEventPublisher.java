package com.loopers.domain.event;

/** Publishes an immutable, committed fact. Publication is not atomic with the business database. */
public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
