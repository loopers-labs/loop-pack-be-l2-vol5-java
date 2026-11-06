package com.loopers.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JsonOutboxMessageMatcherTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-30T01:00:00Z");
    private static final String PAYLOAD = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42",
         "orderId":42,"confirmedAt":"2026-10-30T01:00:00Z",
         "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
        """;
    private final JsonOutboxMessageMatcher matcher = new JsonOutboxMessageMatcher(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void comparesOnlyImmutableFactsAndNeverResetsDeliveryState(boolean published) {
        var expected = message(PAYLOAD, CREATED_AT.plusSeconds(100));
        var actual = message(PAYLOAD, CREATED_AT);
        actual.beginAttempt(CREATED_AT, CREATED_AT.plusSeconds(17));
        if (published) {
            actual.markPublished(1, CREATED_AT.plusSeconds(1));
        } else {
            actual.markFailed(1, CREATED_AT.plusSeconds(30), "SEND_FAILED");
        }
        var before = deliveryState(actual);

        assertThat(matcher.matches(expected, actual)).isTrue();

        assertThat(deliveryState(actual)).isEqualTo(before);
        assertThat(actual.getPayload()).isEqualTo(PAYLOAD);
        assertThat(expected.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(expected.getAttemptCount()).isZero();
    }

    @Test
    void whitespaceAndObjectPropertyOrderDoNotChangeTheSavedFact() {
        String reordered = """
            {
              "items": [{"quantity": 2, "productId": 7}, {"quantity": 1, "productId": 8}],
              "confirmedAt": "2026-10-30T01:00:00Z", "orderId": 42,
              "eventId": "order-confirmed:v1:42", "schemaVersion": 1, "eventType": "OrderConfirmed"
            }
            """;
        var actual = message(reordered, CREATED_AT);

        assertThat(matcher.matches(message(PAYLOAD, CREATED_AT), actual)).isTrue();
        assertThat(actual.getPayload()).isEqualTo(reordered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "aggregateType", "aggregateId", "eventType", "schemaVersion", "topic", "messageKey"})
    void differingImmutableMetadataIsAConflictEvenWithIdenticalJson(String changed) {
        var actual = OutboxMessage.pending(
            changed.equals("eventId") ? "other-event" : "order-confirmed:v1:42",
            changed.equals("aggregateType") ? "Other" : "Order",
            changed.equals("aggregateId") ? "43" : "42",
            changed.equals("eventType") ? "OtherEvent" : "OrderConfirmed",
            changed.equals("schemaVersion") ? 2 : 1,
            changed.equals("topic") ? "other-topic" : "order-events",
            changed.equals("messageKey") ? "43" : "42", PAYLOAD, CREATED_AT);

        assertThat(matcher.matches(message(PAYLOAD, CREATED_AT), actual)).isFalse();
        assertThat(actual.getPayload()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("conflictingPayloads")
    void invalidOrDifferentPayloadCannotMatch(String payload) {
        assertThat(matcher.matches(message(PAYLOAD, CREATED_AT), message(payload, CREATED_AT))).isFalse();
    }

    static Stream<String> conflictingPayloads() {
        return Stream.of(
            PAYLOAD.replace("\"quantity\":2", "\"quantity\":3"),
            PAYLOAD.replace("\"orderId\":42", "\"orderId\":42,\"extra\":true"),
            PAYLOAD.replace("\"orderId\":42", "\"orderId\":42,\"orderId\":42"),
            PAYLOAD.replace("\"quantity\":2", "\"quantity\":2,\"quantity\":2"),
            PAYLOAD + " {}",
            "{\"orderId\":",
            "null",
            "[]");
    }

    @Test
    void identicalMalformedPayloadsAreNotEvidenceOfMatchingFacts() {
        String invalid = PAYLOAD + " {}";
        assertThat(matcher.matches(message(invalid, CREATED_AT), message(invalid, CREATED_AT))).isFalse();
    }

    @Test
    void itemArrayOrderIsNotSilentlyRewrittenBeyondTheCanonicalProducerContract() {
        String reordered = PAYLOAD.replace("{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}",
            "{\"productId\":8,\"quantity\":1},{\"productId\":7,\"quantity\":2}");
        assertThat(matcher.matches(message(PAYLOAD, CREATED_AT), message(reordered, CREATED_AT))).isFalse();
    }

    @Test
    void missingMessagesCannotMatch() {
        var expected = message(PAYLOAD, CREATED_AT);
        assertThat(matcher.matches(expected, null)).isFalse();
        assertThat(matcher.matches(null, expected)).isFalse();
        assertThat(matcher.matches(null, null)).isFalse();
    }

    private OutboxMessage message(String payload, Instant createdAt) {
        return OutboxMessage.pending("order-confirmed:v1:42", "Order", "42", "OrderConfirmed", 1,
            "order-events", "42", payload, createdAt);
    }

    private DeliveryState deliveryState(OutboxMessage message) {
        return new DeliveryState(message.getStatus(), message.getAttemptCount(), message.getCreatedAt(),
            message.getNextAttemptAt(), message.getPublishedAt(), message.getLastError());
    }

    private record DeliveryState(OutboxStatus status, int attempts, Instant createdAt, Instant nextAttemptAt,
                                  Instant publishedAt, String lastError) { }
}
