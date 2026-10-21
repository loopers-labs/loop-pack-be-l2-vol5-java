package com.loopers.domain.payment;

import java.time.Duration;

public record LookupBackoff(Duration initialDelay, int multiplier, Duration maxDelay) {
    public Duration ceiling(int actualAttempts) {
        long maximum = maxDelay.toMillis();
        long delay = Math.min(initialDelay.toMillis(), maximum);
        for (int index = 0; index < actualAttempts && delay < maximum; index++) {
            delay = delay > maximum / multiplier ? maximum : Math.min(maximum, delay * multiplier);
        }
        return Duration.ofMillis(delay);
    }
}
