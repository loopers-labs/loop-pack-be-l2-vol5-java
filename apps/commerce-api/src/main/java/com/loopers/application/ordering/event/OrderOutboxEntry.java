package com.loopers.application.ordering.event;

import java.time.Instant;
import java.util.UUID;

public record OrderOutboxEntry(
    UUID eventId, long orderId, String eventType, int schemaVersion, String topic, String key, byte[] payload, Instant createdAt
) {}
