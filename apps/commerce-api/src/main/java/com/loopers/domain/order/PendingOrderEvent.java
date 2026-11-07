package com.loopers.domain.order;

public record PendingOrderEvent(Long id, String eventId, String topic, String key, byte[] payload,
                                int attemptCount) {
}
