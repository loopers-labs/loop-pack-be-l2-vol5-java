package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.activity.PurchaseActivityCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OrderConfirmedMessageParserTest {
    private final OrderConfirmedMessageParser parser =
        new OrderConfirmedMessageParser(new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
         "confirmedAt":"2026-09-07T01:00:00Z","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
        """;

    @DisplayName("계약대로의 OrderConfirmed 를 읽는다. 모르는 속성은 무시한다.")
    @Test
    void parsesValidMessage() {
        PurchaseActivityCommand command = parser.parse("42", json(VALID.replace("\"orderId\":42,", "\"orderId\":42,\"extra\":true,")));

        assertThat(command.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(command.orderId()).isEqualTo(42L);
        assertThat(command.confirmedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00Z"));
        assertThat(command.items()).extracting(PurchaseActivityCommand.Item::productId, PurchaseActivityCommand.Item::quantity)
            .containsExactly(tuple(7L, 2), tuple(8L, 1));
    }

    @DisplayName("형식·eventType·schemaVersion·key·confirmedAt 이 계약과 다르면 InvalidOrderConfirmedMessageException.")
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "JSON 아님        | 42 | not-json",
        "eventType 다름   | 42 | \"eventType\":\"OrderConfirmed\"=>\"eventType\":\"OrderCanceled\"",
        "schemaVersion 2 | 42 | \"schemaVersion\":1=>\"schemaVersion\":2",
        "schemaVersion 없음 | 42 | \"schemaVersion\":1,=>",
        "key 불일치       | 43 | ",
        "confirmedAt 형식  | 42 | 2026-09-07T01:00:00Z=>2026-09-07 01:00",
        "confirmedAt 없음  | 42 | \"confirmedAt\":\"2026-09-07T01:00:00Z\",=>"
    })
    void rejectsContractViolations(String name, String key, String change) {
        byte[] value;
        if ("not-json".equals(change)) {
            value = json("not-json");
        } else if (change == null) {
            value = json(VALID);
        } else {
            String[] fromTo = change.split("=>", -1);
            value = json(VALID.replace(fromTo[0], fromTo[1]));
        }

        assertThatThrownBy(() -> parser.parse(key, value)).isInstanceOf(InvalidOrderConfirmedMessageException.class);
    }

    @DisplayName("value 가 없으면 InvalidOrderConfirmedMessageException.")
    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> parser.parse("42", null)).isInstanceOf(InvalidOrderConfirmedMessageException.class);
    }
}
