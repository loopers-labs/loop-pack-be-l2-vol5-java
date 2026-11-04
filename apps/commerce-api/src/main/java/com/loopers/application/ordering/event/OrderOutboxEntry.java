package com.loopers.application.ordering.event;

import java.time.Instant;
import java.util.UUID;

public record OrderOutboxEntry(
    UUID eventId, long orderId, String eventType, int schemaVersion, String topic, String key, byte[] payload, Instant createdAt
) {
    public boolean sameEnvelope(OrderOutboxEntry other) {
        return eventId.equals(other.eventId) && orderId == other.orderId && eventType.equals(other.eventType)
            && schemaVersion == other.schemaVersion && topic.equals(other.topic) && key.equals(other.key)
            && createdAt.equals(other.createdAt);
    }
}
