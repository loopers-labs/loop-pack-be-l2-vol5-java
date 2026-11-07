package com.loopers.infrastructure.order;

enum OrderEventOutboxStatus {
    PENDING,
    PUBLISHED,
    BLOCKED
}
