package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.OrderConfirmationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedMessageParserTest {
    static final String JSON = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
         "confirmedAt":"2026-10-29T01:00:00.123456Z",
         "items":[{"productId":8,"quantity":3},{"productId":7,"quantity":2},{"productId":7,"quantity":3}]}
        """;
    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser(new ObjectMapper());

    @Test
    void parsesProducerContractAndNormalizesStoredFacts() {
        OrderConfirmationCommand purchase = parser.parse("42", JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(purchase.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(purchase.orderId()).isEqualTo(42L);
        assertThat(purchase.confirmedAt()).isEqualTo(Instant.parse("2026-10-29T01:00:00.123456Z"));
        assertThat(purchase.items()).containsExactly(
            new OrderConfirmationCommand.Item(7L, 5), new OrderConfirmationCommand.Item(8L, 3));
    }

    @ParameterizedTest
    @MethodSource("invalidRecords")
    void rejectsInvalidContractBeforeBusinessHandling(String key, String json) {
        byte[] value = json == null ? null : json.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(key, value)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidRecords() {
        return Stream.of(
            Arguments.of(null, JSON),
            Arguments.of("43", JSON),
            Arguments.of("042", JSON),
            Arguments.of("42", null),
            Arguments.of("42", ""),
            Arguments.of("42", "null"),
            Arguments.of("42", "[]"),
            Arguments.of("42", "{broken"),
            Arguments.of("42", JSON + JSON),
            Arguments.of("42", JSON.replace("OrderConfirmed", "OrderCancelled")),
            Arguments.of("42", JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
            Arguments.of("42", JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")),
            Arguments.of("42", JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")),
            Arguments.of("42", JSON.replace("\"eventId\":\"order-confirmed:v1:42\",", "")),
            Arguments.of("42", JSON.replace("order-confirmed:v1:42", " ")),
            Arguments.of("42", JSON.replace("\"orderId\":42", "\"orderId\":0")),
            Arguments.of("42", JSON.replace("\"orderId\":42", "\"orderId\":\"42\"")),
            Arguments.of("42", JSON.replace("\"orderId\":42", "\"orderId\":42.5")),
            Arguments.of("42", JSON.replace("2026-10-29T01:00:00.123456Z", "invalid-time")),
            Arguments.of("42", JSON.replace(".123456Z", ".123456789Z")),
            Arguments.of("42", JSON.replace("\"items\":[", "\"otherItems\":[")),
            Arguments.of("42", JSON.replace("\"productId\":7", "\"productId\":0")),
            Arguments.of("42", JSON.replace("\"productId\":7", "\"productId\":9223372036854775808")),
            Arguments.of("42", JSON.replace("\"quantity\":2", "\"quantity\":0")),
            Arguments.of("42", JSON.replace("\"quantity\":2", "\"quantity\":2.5")),
            Arguments.of("42", JSON.replace("\"quantity\":2", "\"quantity\":\"2\"")),
            Arguments.of("42", JSON.replace("\"quantity\":2", "\"quantity\":2147483647"))
        );
    }
}
