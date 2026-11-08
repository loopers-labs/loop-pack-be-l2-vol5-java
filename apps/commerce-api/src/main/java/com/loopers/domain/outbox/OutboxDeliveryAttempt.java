package com.loopers.domain.outbox;

/** A detached immutable snapshot of one committed delivery start. It carries no JPA state into network waits. */
public record OutboxDeliveryAttempt(long id, String eventId, String topic, String messageKey,
                                    String payload, int attemptCount) {
    public OutboxDeliveryAttempt {
        if (id <= 0 || attemptCount <= 0 || eventId == null || eventId.isBlank()
            || topic == null || topic.isBlank() || messageKey == null || messageKey.isBlank()
            || payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("A delivery attempt requires persisted identity and message facts");
        }
    }
}
