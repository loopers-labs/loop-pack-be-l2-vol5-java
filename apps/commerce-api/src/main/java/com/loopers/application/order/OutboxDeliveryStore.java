package com.loopers.application.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxDeliveryStore {
    int blockExpiredAttempts(Instant now);

    List<String> readyEventIds(Instant now, int limit);

    Optional<DeliveryEvent> beginAttempt(String eventId, Instant now, Instant retryAt);

    void markPublished(String eventId, Instant now);

    void recordFailure(String eventId, Instant nextAttemptAt, String error);

    boolean resume(String eventId, Instant now);

    record DeliveryEvent(String eventId, String destinationTopic, String messageKey, String payload, int attemptInCycle) {}
}
