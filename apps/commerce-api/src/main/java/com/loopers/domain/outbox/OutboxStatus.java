package com.loopers.domain.outbox;

/** PUBLISHED means broker acknowledgement was recorded, not that a consumer finished its work. */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
