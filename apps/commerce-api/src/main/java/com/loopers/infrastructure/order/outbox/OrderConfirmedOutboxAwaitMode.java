package com.loopers.infrastructure.order.outbox;

public enum OrderConfirmedOutboxAwaitMode {
    BOUNDED_WAIT,
    CALLBACK
}
