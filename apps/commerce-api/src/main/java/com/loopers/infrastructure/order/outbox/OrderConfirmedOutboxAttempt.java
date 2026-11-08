package com.loopers.infrastructure.order.outbox;

public record OrderConfirmedOutboxAttempt(
    String eventId,
    Long orderId,
    String messageKey,
    String payload,
    int attemptNumber
) {}
