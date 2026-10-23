package com.loopers.application.order;

import java.time.Duration;

/** Monotonic request timing. Injectable without sleeping for minutes in contract tests. */
public interface PaymentConfirmationTiming {
    long nanoTime();

    void sleep(Duration duration) throws InterruptedException;

    double jitterFactor();
}
