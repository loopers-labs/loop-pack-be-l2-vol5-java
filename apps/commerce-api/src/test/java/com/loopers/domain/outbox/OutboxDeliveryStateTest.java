package com.loopers.domain.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDeliveryStateTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00.123456Z");

    @Test
    void startsOnlyWhenDueAndLeavesTheMessagePendingWithARecoveryTime() {
        OutboxMessage message = message();
        var facts = facts(message);
        assertThat(message.isDue(NOW.minusNanos(1))).isFalse();
        assertThat(message.isDue(NOW)).isTrue();
        assertThat(message.beginAttempt(NOW, NOW.plusSeconds(17))).isEqualTo(1);
        assertThat(message.getAttemptCount()).isEqualTo(1);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(17));
        assertThat(message.getPublishedAt()).isNull();
        assertThat(message.isDue(NOW)).isFalse();
        assertThat(facts(message)).isEqualTo(facts);
        assertThatThrownBy(() -> message.beginAttempt(NOW, NOW.plusSeconds(17)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(message.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void failureRetainsTheOriginalFactAndTheNextAttemptHasItsOwnIdentity() {
        OutboxMessage message = message();
        var facts = facts(message);
        int first = message.beginAttempt(NOW, NOW.plusSeconds(17));
        assertThat(message.markFailed(first, NOW.plusSeconds(5), "TIMEOUT")).isTrue();
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getLastError()).isEqualTo("TIMEOUT");
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(message.isDue(NOW.plusSeconds(5))).isTrue();
        int second = message.beginAttempt(NOW.plusSeconds(5), NOW.plusSeconds(27));
        assertThat(second).isEqualTo(2);
        assertThat(message.markPublished(first, NOW.plusSeconds(6))).isFalse();
        assertThat(message.markFailed(first, NOW.plusSeconds(99), "SEND_FAILED")).isFalse();
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(27));
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(facts(message)).isEqualTo(facts);
    }

    @Test
    void confirmedPublicationCannotBeReopenedByAnyLateResult() {
        OutboxMessage message = message();
        var facts = facts(message);
        int first = message.beginAttempt(NOW, NOW.plusSeconds(17));
        message.markFailed(first, NOW.plusSeconds(5), "SEND_FAILED");
        int attempt = message.beginAttempt(NOW.plusSeconds(5), NOW.plusSeconds(27));
        assertThat(message.markPublished(attempt, NOW.plusSeconds(6).plusNanos(123456789))).isTrue();
        Instant published = Instant.parse("2026-11-04T00:00:06.246912Z");
        assertThat(message.getPublishedAt()).isEqualTo(published);
        assertThat(message.getLastError()).isNull();
        assertThat(message.markPublished(attempt, NOW.plusSeconds(9))).isFalse();
        assertThat(message.markFailed(attempt, NOW.plusSeconds(90), "TIMEOUT")).isFalse();
        assertThat(message.isDue(NOW.plusSeconds(999))).isFalse();
        assertThat(message.getPublishedAt()).isEqualTo(published);
        assertThat(facts(message)).isEqualTo(facts);
        assertThatThrownBy(() -> message.beginAttempt(NOW.plusSeconds(999), NOW.plusSeconds(1000)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resultsBeforeAnyStartedAttemptCannotPublishOrChangeTheRetryTime() {
        OutboxMessage message = message();
        assertThat(message.markPublished(0, NOW)).isFalse();
        assertThat(message.markFailed(0, NOW.plusSeconds(5), "TIMEOUT")).isFalse();
        assertThat(message.markPublished(1, NOW)).isFalse();
        assertThat(message.getAttemptCount()).isZero();
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void invalidAttemptTimesAndUnsafeErrorsDoNotMutateTheRow() {
        OutboxMessage message = message();
        assertThatThrownBy(() -> message.beginAttempt(NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message.beginAttempt(NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(message.getAttemptCount()).isZero();
        message.beginAttempt(NOW, NOW.plusSeconds(17));
        assertThatThrownBy(() -> message.markFailed(1, NOW.plusSeconds(5), "cardNo=private"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
        assertThatThrownBy(() -> message.markPublished(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(message.getLastError()).isNull();
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(17));
        assertThat(message.getPublishedAt()).isNull();
    }

    private OutboxMessage message() {
        return OutboxMessage.pending("order-confirmed:v1:42", "Order", "42", "OrderConfirmed", 1,
            "stored-topic", "42", "{ \"eventId\": \"order-confirmed:v1:42\" }", NOW);
    }

    private List<Object> facts(OutboxMessage message) {
        return List.of(message.getEventId(), message.getAggregateType(), message.getAggregateId(),
            message.getEventType(), message.getSchemaVersion(), message.getTopic(), message.getMessageKey(),
            message.getPayload(), message.getCreatedAt());
    }
}
