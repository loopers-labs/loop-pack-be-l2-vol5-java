package com.loopers.infrastructure.order.outbox;

public enum OrderConfirmedOutboxStatus {
    PENDING,
    PUBLISHED,
    BLOCKED
}
