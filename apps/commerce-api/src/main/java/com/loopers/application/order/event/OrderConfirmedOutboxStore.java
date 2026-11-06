package com.loopers.application.order.event;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface OrderConfirmedOutboxStore {
    void savePending(OrderConfirmedMessageV1 message, String payload, ZonedDateTime nextAttemptAt);

    Optional<StoredMessage> findByEventId(String eventId);

    record StoredMessage(String eventId, Long orderId, String eventType, int schemaVersion,
                         String messageKey, String payload) {}
}
