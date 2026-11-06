package com.loopers.application.order;

public record OrderOutboxMessage(String eventId, long orderId, String eventType, int schemaVersion,
        String messageKey, String destinationTopic, String payload) {
    public OrderOutboxMessage {
        if (eventId == null || eventId.isBlank() || orderId <= 0 || eventType == null || eventType.isBlank()
                || schemaVersion <= 0 || messageKey == null || messageKey.isBlank()
                || destinationTopic == null || destinationTopic.isBlank() || payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Order outbox message metadata and payload are required");
        }
    }
}
