package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxMessageSerializer;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class JsonOutboxMessageSerializer implements OutboxMessageSerializer {
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<Class<?>, OutboxEventMapping<?>> mappings;

    public JsonOutboxMessageSerializer(ObjectMapper mapper, Clock clock, List<OutboxEventMapping<?>> mappings) {
        this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<Class<?>, OutboxEventMapping<?>> indexed = new HashMap<>();
        for (var mapping : mappings) {
            if (indexed.putIfAbsent(mapping.eventClass(), mapping) != null) {
                throw new IllegalArgumentException("Only one Outbox mapping per event class is allowed");
            }
        }
        this.mappings = Map.copyOf(indexed);
    }

    @Override
    public OutboxMessage serialize(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        var mapping = mappings.get(event.getClass());
        if (mapping == null) {
            throw new IllegalArgumentException("No Outbox mapping is registered for this event");
        }
        try {
            String payload = mapper.writeValueAsString(event);
            return OutboxMessage.pending(event.eventId(), mapping.aggregateType(), mapping.aggregateIdOf(event),
                event.eventType(), mapping.versionOf(event), mapping.topic(), event.key(), payload, clock.instant());
        } catch (JsonProcessingException exception) {
            // Do not attach Jackson's potentially payload-bearing exception to externally logged failures.
            throw new IllegalStateException("Could not serialize external event");
        }
    }
}
