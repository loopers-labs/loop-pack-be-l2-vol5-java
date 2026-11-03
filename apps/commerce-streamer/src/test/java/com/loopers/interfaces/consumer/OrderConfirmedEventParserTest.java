package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.domain.purchase.OrderConfirmation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedEventParserTest {
    private static final String JSON = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
         "confirmedAt":"2026-10-29T00:00:00.123456789Z","items":[{"productId":7,"quantity":2}]}
        """;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderConfirmedEventParser parser = new OrderConfirmedEventParser(mapper);

    @Test
    void parsesRequiredFactsAndPreservesTheFullConfirmationInstant() {
        var event = parse(JSON);

        assertThat(event).isEqualTo(new OrderConfirmation("OrderConfirmed", 1, "order-confirmed:v1:42", 42,
            Instant.parse("2026-10-29T00:00:00.123456789Z"), List.of(new OrderConfirmation.Item(7, 2))));
    }

    @Test
    void normalizesDifferentPropertyOrderAndSplitItemsWithoutKeepingUnknownFields() {
        String reordered = """
            {"items":[{"quantity":1,"productId":7},{"productId":7,"quantity":1}],
             "confirmedAt":"2026-10-29T09:00:00.123456789+09:00","orderId":42,
             "eventId":"order-confirmed:v1:42","schemaVersion":1,"eventType":"OrderConfirmed",
             "unusedPrivateField":"never-retain-this"}
            """;

        assertThat(parse(reordered)).isEqualTo(parse(JSON));
        assertThat(parse(reordered).toString()).doesNotContain("never-retain-this", "unusedPrivateField");
    }

    @Test
    void acceptsDifferentEventIdsSoTheStoredFactCanDetectOrderIdentityConflicts() {
        var changed = parse(JSON.replace("order-confirmed:v1:42", "different-event"));

        assertThat(changed.eventId()).isEqualTo("different-event");
        assertThat(changed.orderId()).isEqualTo(42L);
        assertThat(changed).isNotEqualTo(parse(JSON));
    }

    @Test
    void acceptsMaximumNumericValuesWithoutLoss() {
        String json = JSON.replace("\"orderId\":42", "\"orderId\":" + Long.MAX_VALUE)
            .replace("\"productId\":7", "\"productId\":" + Long.MAX_VALUE)
            .replace("\"quantity\":2", "\"quantity\":" + Integer.MAX_VALUE);

        var event = parser.parseAndValidate(Long.toString(Long.MAX_VALUE), bytes(json));

        assertThat(event.orderId()).isEqualTo(Long.MAX_VALUE);
        assertThat(event.items()).containsExactly(new OrderConfirmation.Item(Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"043", "042", "+42", " 42", "43", "abc"})
    void rejectsMissingNonCanonicalAndMismatchingKeys(String key) {
        assertThatThrownBy(() -> parser.parseAndValidate(key, bytes(JSON))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsWrongJsonTypesOutOfRangeAndInvalidContractValues(String original, String replacement) {
        assertThatThrownBy(() -> parse(JSON.replace(original, replacement))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventType", "schemaVersion", "eventId", "orderId", "confirmedAt", "items",
        "items.productId", "items.quantity"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(JSON);
        if (field.startsWith("items.")) {
            ((ObjectNode) json.path("items").get(0)).remove(field.substring("items.".length()));
        } else {
            json.remove(field);
        }

        assertThatThrownBy(() -> parser.parseAndValidate("42", mapper.writeValueAsBytes(json)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "\"text\"", "{}"})
    void rejectsEmptyAndNonEventDocuments(String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTombstonesInsteadOfTreatingThemAsCompletedWork() {
        assertThatThrownBy(() -> parser.parseAndValidate("42", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmbiguousDuplicateJsonKeysAndTrailingDocuments() {
        assertThatThrownBy(() -> parse(JSON.replace("\"orderId\":42", "\"orderId\":42,\"orderId\":43")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(JSON + "{}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidJsonWithoutRetainingTheRawPayloadInTheException() {
        String raw = "{\"cardNo\":\"0000-0000-0000-0000\",\"broken\":}";

        assertThatThrownBy(() -> parse(raw)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("0000-0000-0000-0000").hasNoCause();
    }

    private OrderConfirmation parse(String json) {
        return parser.parseAndValidate("42", bytes(json));
    }

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Stream<Arguments> invalidValues() {
        return Stream.of(
            Arguments.of("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            Arguments.of("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            Arguments.of("\"schemaVersion\":1", "\"schemaVersion\":2147483648"),
            Arguments.of("\"schemaVersion\":1", "\"schemaVersion\":2"),
            Arguments.of("\"orderId\":42", "\"orderId\":\"42\""),
            Arguments.of("\"orderId\":42", "\"orderId\":42.0"),
            Arguments.of("\"orderId\":42", "\"orderId\":9223372036854775808"),
            Arguments.of("\"orderId\":42", "\"orderId\":0"),
            Arguments.of("\"productId\":7", "\"productId\":\"7\""),
            Arguments.of("\"productId\":7", "\"productId\":7.0"),
            Arguments.of("\"productId\":7", "\"productId\":9223372036854775808"),
            Arguments.of("\"productId\":7", "\"productId\":-1"),
            Arguments.of("\"quantity\":2", "\"quantity\":\"2\""),
            Arguments.of("\"quantity\":2", "\"quantity\":2.0"),
            Arguments.of("\"quantity\":2", "\"quantity\":2147483648"),
            Arguments.of("\"quantity\":2", "\"quantity\":0"),
            Arguments.of("\"OrderConfirmed\"", "\"OrderCancelled\""),
            Arguments.of("\"eventId\":\"order-confirmed:v1:42\"", "\"eventId\":\" \""),
            Arguments.of("\"eventId\":\"order-confirmed:v1:42\"", "\"eventId\":42"),
            Arguments.of("\"confirmedAt\":\"2026-10-29T00:00:00.123456789Z\"", "\"confirmedAt\":123"),
            Arguments.of("\"2026-10-29T00:00:00.123456789Z\"", "\"2026-10-29\""),
            Arguments.of("[{\"productId\":7,\"quantity\":2}]", "[]"),
            Arguments.of("[{\"productId\":7,\"quantity\":2}]", "{}"),
            Arguments.of("[{\"productId\":7,\"quantity\":2}]", "[null]"));
    }
}
