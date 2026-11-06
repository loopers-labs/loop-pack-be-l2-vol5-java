package com.loopers.application.outbox;

import java.time.Duration;

/** Retry eligibility after a failed attempt; scheduling/backlog can make the actual next start later. */
public class OutboxRetryPolicy {
    private final Duration initial;
    private final Duration maximum;

    public OutboxRetryPolicy(Duration initial, Duration maximum) {
        if (initial == null || maximum == null || initial.isZero() || initial.isNegative()
            || maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("Outbox retry durations must be positive and ordered");
        }
        this.initial = initial;
        this.maximum = maximum;
    }

    public Duration delayFor(int startedAttempt) {
        if (startedAttempt < 1) {
            throw new IllegalArgumentException("Retry delay requires a started attempt");
        }
        Duration delay = initial;
        for (int step = 1; step < startedAttempt && delay.compareTo(maximum) < 0; step++) {
            if (delay.compareTo(maximum.minus(delay)) >= 0) {
                return maximum;
            }
            delay = delay.multipliedBy(2);
        }
        return delay;
    }
}
