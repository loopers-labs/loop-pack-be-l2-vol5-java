package com.loopers.infrastructure.event;

import com.loopers.domain.event.DomainEvent;

import java.util.Objects;

/** Exact event-class allowlist. Topic and enablement are transport configuration, not domain data. */
public record KafkaEventRoute(Class<? extends DomainEvent> eventClass, String topic, boolean enabled) {
    public KafkaEventRoute {
        Objects.requireNonNull(eventClass, "eventClass");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("An event route requires a topic");
        }
    }
}
