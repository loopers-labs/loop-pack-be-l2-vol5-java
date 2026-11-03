package com.loopers.domains.order.app;

import java.time.Duration;

public final class OutboxRetryPolicy {
    private OutboxRetryPolicy() {
    }

    public static Duration backoff(int attemptCount) {
        return Duration.ZERO;
    }
}
