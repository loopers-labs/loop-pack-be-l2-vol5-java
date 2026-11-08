package com.loopers.application.outbox;

import java.time.Duration;

/** Initial experiment settings, not delivery-latency guarantees. */
public record OutboxRelaySettings(int batchSize, Duration fixedDelay, Duration sendBudget,
                                  Duration retryInitial, Duration retryMax) {
    public OutboxRelaySettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox batch size must be positive");
        }
        positive(fixedDelay);
        positive(sendBudget);
        positive(retryInitial);
        positive(retryMax);
        if (fixedDelay.toMillis() < 1 || retryInitial.compareTo(retryMax) > 0) {
            throw new IllegalArgumentException("Invalid Outbox scheduling or retry interval");
        }
    }

    private static void positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Outbox durations must be positive");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Outbox duration exceeds the supported range");
        }
    }
}
