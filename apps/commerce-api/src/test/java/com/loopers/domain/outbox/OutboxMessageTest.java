package com.loopers.domain.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMessageTest {
    private static final Instant RECORDED_AT = Instant.parse("2026-11-04T01:02:03.123456789Z");
    private static final String PAYLOAD = "{\"eventId\":\"order-confirmed:v1:42\",\"items\":[{\"productId\":7,\"quantity\":2}]}";

    @Test
    void recordsImmutableFactsAndStartsPendingWithoutClaimingABrokerAttempt() {
        OutboxMessage message = pending("order-confirmed:v1:42", "Order", "42", "OrderConfirmed", 1,
            "order-events", "42", PAYLOAD, RECORDED_AT);

        assertThat(message.getId()).isNull();
        assertThat(message.getEventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(message.getAggregateType()).isEqualTo("Order");
        assertThat(message.getAggregateId()).isEqualTo("42");
        assertThat(message.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(message.getSchemaVersion()).isEqualTo(1);
        assertThat(message.getTopic()).isEqualTo("order-events");
        assertThat(message.getMessageKey()).isEqualTo("42");
        assertThat(message.getPayload()).isEqualTo(PAYLOAD);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getAttemptCount()).isZero();
        assertThat(message.getCreatedAt()).isEqualTo(Instant.parse("2026-11-04T01:02:03.123456Z"));
        assertThat(message.getNextAttemptAt()).isEqualTo(message.getCreatedAt());
        assertThat(message.getPublishedAt()).isNull();
        assertThat(message.getLastError()).isNull();
    }

    @Test
    void preservesTheCompletedJsonExactlyWithoutNormalizingOrDoubleEncodingIt() {
        String completedJson = "{ \"name\": \"한글 🍀\", \"items\": [2, 1] }\n";
        OutboxMessage message = pending("sample:1", "Sample", "one", "SampleCreated", 2,
            "sample-events", "key", completedJson, RECORDED_AT);

        assertThat(message.getPayload()).isEqualTo(completedJson);
        assertThat(message.getSchemaVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsMissingOrUnstorableMetadataWithoutLeakingThePayload(int position, Object invalid) {
        Object[] values = {"order-confirmed:v1:42", "Order", "42", "OrderConfirmed", 1,
            "order-events", "42", PAYLOAD, RECORDED_AT};
        values[position] = invalid;

        assertThatThrownBy(() -> pending((String) values[0], (String) values[1], (String) values[2],
            (String) values[3], (Integer) values[4], (String) values[5], (String) values[6],
            (String) values[7], (Instant) values[8]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining(PAYLOAD);
    }

    static Stream<Arguments> invalidMetadata() {
        return Stream.of(
            Arguments.of(0, null), Arguments.of(0, " "), Arguments.of(0, "x".repeat(256)),
            Arguments.of(1, null), Arguments.of(1, ""), Arguments.of(1, "x".repeat(101)),
            Arguments.of(2, null), Arguments.of(2, "\t"), Arguments.of(2, "x".repeat(129)),
            Arguments.of(3, null), Arguments.of(3, ""), Arguments.of(3, "x".repeat(101)),
            Arguments.of(4, 0), Arguments.of(4, -1),
            Arguments.of(5, null), Arguments.of(5, " "), Arguments.of(5, "x".repeat(250)),
            Arguments.of(6, null), Arguments.of(6, " "), Arguments.of(6, "x".repeat(256)),
            Arguments.of(7, null), Arguments.of(7, "\n"), Arguments.of(8, null)
        );
    }

    private static OutboxMessage pending(String eventId, String aggregateType, String aggregateId,
                                         String eventType, int version, String topic, String key,
                                         String payload, Instant createdAt) {
        return OutboxMessage.pending(eventId, aggregateType, aggregateId, eventType, version, topic, key, payload, createdAt);
    }
}
