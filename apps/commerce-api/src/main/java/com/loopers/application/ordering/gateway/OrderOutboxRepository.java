package com.loopers.application.ordering.gateway;

import com.loopers.application.ordering.event.OrderOutboxEntry;
import com.loopers.application.ordering.gateway.OrderEventTransport.Published;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderOutboxRepository {
    void insert(OrderOutboxEntry entry);

    List<Candidate> due(Instant now, int limit);

    boolean reserve(Candidate candidate, Instant now, Instant nextAttemptAt);

    boolean publish(Candidate candidate, Instant now, Published delivery);

    boolean fail(Candidate candidate, Instant nextAttemptAt, String reason, boolean exhausted);

    void blockExhausted(Instant now, int maxAttempts);

    boolean resume(UUID eventId, Instant now);

    record Candidate(UUID eventId, String topic, String key, byte[] payload, int attempts) {}
}
