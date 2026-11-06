package com.loopers.infrastructure.outbox;

import com.loopers.domain.event.DomainEvent;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Explicit event-class mapping; new facts add a mapping without adding another transaction listener. */
public record OutboxEventMapping<T extends DomainEvent>(Class<T> eventClass, String aggregateType,
                                                       ToIntFunction<T> schemaVersion,
                                                       Function<T, String> aggregateId, String topic) {
    public OutboxEventMapping {
        Objects.requireNonNull(eventClass, "eventClass");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateType == null || aggregateType.isBlank() || topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Outbox mapping requires a source type and destination");
        }
    }

    int versionOf(DomainEvent event) {
        return schemaVersion.applyAsInt(eventClass.cast(event));
    }

    String aggregateIdOf(DomainEvent event) {
        return aggregateId.apply(eventClass.cast(event));
    }
}
