package com.loopers.application.order;

public final class OutboxRetryPolicy {
    public static final int MAX_ATTEMPTS = 3;

    private OutboxRetryPolicy() {
    }
}
