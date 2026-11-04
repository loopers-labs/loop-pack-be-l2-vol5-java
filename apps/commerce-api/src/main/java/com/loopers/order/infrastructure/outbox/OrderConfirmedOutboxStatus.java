package com.loopers.order.infrastructure.outbox;

public enum OrderConfirmedOutboxStatus {
    PENDING,
    FAILED,
    BLOCKED,
    PUBLISHED
}
