package com.loopers.application.order;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OrderOutboxRelay {
    private static final int BATCH_SIZE = 50;
    private final OutboxDeliveryStore store;
    private final OutboxKafkaSender sender;
    private final Clock clock;
    private final LongUnaryOperator jitter;
    private final AtomicBoolean running = new AtomicBoolean();

    public OrderOutboxRelay(OutboxDeliveryStore store, OutboxKafkaSender sender,
            Clock clock, LongUnaryOperator jitter) {
        this.store = store;
        this.sender = sender;
        this.clock = clock;
        this.jitter = jitter;
    }

    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int blocked = store.blockExpiredAttempts(clock.instant());
            if (blocked > 0) {
                log.warn("order_outbox_expired_attempts_blocked count={}", blocked);
            }
            for (String eventId : store.readyEventIds(clock.instant(), BATCH_SIZE)) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                deliver(eventId);
            }
        } finally {
            running.set(false);
        }
    }

    public void resume(String eventId) {
        if (!store.resume(eventId, clock.instant())) {
            throw new IllegalArgumentException("Blocked outbox event not found");
        }
    }

    private void deliver(String eventId) {
        Instant now = clock.instant();
        var event = store.beginAttempt(eventId, now, now.plusSeconds(5));
        if (event.isEmpty()) {
            return;
        }
        var current = event.get();
        try {
            sender.send(current.destinationTopic(), current.messageKey(), current.payload());
        } catch (RuntimeException failure) {
            Instant retryAt = current.attemptInCycle() >= OutboxRetryPolicy.MAX_ATTEMPTS
                    ? null : clock.instant().plusMillis(delayMillis(current.attemptInCycle()));
            String errorType = errorType(failure);
            store.recordFailure(eventId, retryAt, errorType);
            log.warn("order_outbox_send_failed eventId={} cycleAttempt={} autoRetry={} errorType={}",
                    eventId, current.attemptInCycle(), retryAt != null, errorType);
            return;
        }
        store.markPublished(eventId, clock.instant());
    }

    private long delayMillis(int attempt) {
        long base = 1_000L << (attempt - 1);
        return base + jitter.applyAsLong(base);
    }

    private String errorType(Throwable failure) {
        Throwable current = failure;
        for (int i = 0; i < 8 && current.getCause() != null && current.getCause() != current; i++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }
}
