package com.loopers.application.order;

import java.time.Duration;
import java.util.Objects;

/** One request-entry budget, never reset after TX A, create timeout, or a lookup. */
public final class PaymentConfirmationDeadline {
    public static final Duration REQUEST_TARGET = Duration.ofSeconds(180);
    public static final Duration EXTERNAL_CONFIRMATION_BUDGET = Duration.ofSeconds(175);
    public static final Duration CREATE_CALL_BUDGET = Duration.ofSeconds(65);
    public static final Duration LOOKUP_CALL_BUDGET = Duration.ofSeconds(10);
    private final PaymentConfirmationTiming timing;
    private final long startedNanos;

    private PaymentConfirmationDeadline(PaymentConfirmationTiming timing, long startedNanos) {
        this.timing = Objects.requireNonNull(timing);
        this.startedNanos = startedNanos;
    }

    public static PaymentConfirmationDeadline start(PaymentConfirmationTiming timing) {
        return start(timing, timing.nanoTime());
    }

    public static PaymentConfirmationDeadline start(PaymentConfirmationTiming timing, long startedNanos) {
        return new PaymentConfirmationDeadline(timing, startedNanos);
    }

    public boolean permitsCreate() {
        return remainingNanos() >= CREATE_CALL_BUDGET.toNanos();
    }

    public boolean permitsLookup() {
        return remainingNanos() >= LOOKUP_CALL_BUDGET.toNanos();
    }

    Duration waitBeforeLookup(Duration desired) {
        long available = Math.max(0, remainingNanos() - LOOKUP_CALL_BUDGET.toNanos());
        // Preserve the selected interval, including its jitter minimum. Do not squeeze in an early last GET.
        return desired.toNanos() <= available ? desired : Duration.ZERO;
    }

    private long remainingNanos() {
        // Subtraction is also valid across System.nanoTime's signed wraparound for these short durations.
        long elapsed = timing.nanoTime() - startedNanos;
        return EXTERNAL_CONFIRMATION_BUDGET.toNanos() - Math.max(0, elapsed);
    }
}
