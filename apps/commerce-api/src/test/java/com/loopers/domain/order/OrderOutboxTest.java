package com.loopers.domain.order;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderOutboxTest {
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Duration FIRST = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofSeconds(60);

    private final OrderOutbox outbox = OrderOutbox.pending(
        new OrderConfirmed(42L, NOW, List.of(new OrderConfirmed.Item(7L, 2))), "OrderConfirmed", 1, "{}");

    @Test
    void retryDelayDoublesFromFirstDelayUntilMaximum() {
        assertThat(retryAfterAttempts(1)).isEqualTo(NOW.plusSeconds(2));
        assertThat(retryAfterAttempts(1)).isEqualTo(NOW.plusSeconds(4));
        assertThat(retryAfterAttempts(3)).isEqualTo(NOW.plusSeconds(32));
        assertThat(retryAfterAttempts(1)).isEqualTo(NOW.plusSeconds(60));
        assertThat(retryAfterAttempts(100)).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void failureKeepsEventPendingWithBoundedError() {
        outbox.startAttempt();

        outbox.scheduleRetry(NOW, "x".repeat(300), FIRST, MAX);

        assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getLastError()).hasSize(255);
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    void publishedRecordsConfirmationTimeAndClearsLastError() {
        outbox.startAttempt();
        outbox.scheduleRetry(NOW, "timeout", FIRST, MAX);
        outbox.startAttempt();

        outbox.markPublished(NOW.plusSeconds(3));

        assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(outbox.getLastError()).isNull();
        assertThat(outbox.getAttemptCount()).isEqualTo(2);
    }

    private Instant retryAfterAttempts(int attempts) {
        for (int i = 0; i < attempts; i++) {
            outbox.startAttempt();
        }
        outbox.scheduleRetry(NOW, "failed", FIRST, MAX);
        return outbox.getNextAttemptAt();
    }
}
