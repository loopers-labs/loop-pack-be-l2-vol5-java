package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.OrderConfirmation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedParserTest {
    private final OrderConfirmedParser parser = new OrderConfirmedParser(new ObjectMapper(), List.of(new OrderConfirmedV1Reader()));

    @DisplayName("JSON 속성·품목 순서와 날짜 offset이 달라도 같은 확정 사실로 읽는다.")
    @Test
    void parsesAndNormalizesEquivalentJson() {
        String json = """
            {"items":[{"quantity":1,"productId":8},{"productId":7,"quantity":1},{"productId":7,"quantity":1}],
             "confirmedAt":"2026-09-07T10:00:00.123456+09:00","orderId":42,
             "eventId":"opaque-42","schemaVersion":1,"eventType":"OrderConfirmed"}
            """;

        OrderConfirmation event = parser.parseAndValidate("42", bytes(json));

        assertThat(event).isEqualTo(new OrderConfirmation("opaque-42", 42L,
            Instant.parse("2026-09-07T01:00:00.123456Z"),
            List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1))));
    }

    @DisplayName("key는 payload 주문 ID의 정확한 10진 문자열이어야 한다.")
    @ParameterizedTest
    @MethodSource("invalidKeys")
    void rejectsMismatchedKey(String key) {
        assertThatThrownBy(() -> parser.parseAndValidate(key, bytes(validJson())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> invalidKeys() {
        return Stream.of(null, "", "43", "042");
    }

    @DisplayName("잘못된 형식·버전·필수 값·양수 규칙·숫자 타입을 거절한다.")
    @ParameterizedTest
    @MethodSource("invalidMessages")
    void rejectsInvalidMessages(String json) {
        assertThatThrownBy(() -> parser.parseAndValidate("42", bytes(json)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> invalidMessages() {
        String valid = validJson();
        return Stream.of(
            "{", "null", "[]", valid + "{}",
            valid.replace("OrderConfirmed", "OtherEvent"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            valid.replace("\"orderId\":42", "\"orderId\":\"42\""),
            valid.replace("\"orderId\":42", "\"orderId\":9223372036854775808"),
            valid.replace("\"eventId\":\"event-42\"", "\"eventId\":null"),
            valid.replace("\"eventId\":\"event-42\"", "\"eventId\":\"\""),
            valid.replace("2026-09-07T01:00:00Z", "yesterday"),
            valid.replace("\"productId\":7", "\"productId\":0"),
            valid.replace("\"quantity\":2", "\"quantity\":0"),
            valid.replace("\"quantity\":2", "\"quantity\":2.5"),
            valid.replace("\"quantity\":2", "\"quantity\":2147483648"),
            valid.replace("[{\"productId\":7,\"quantity\":2}]", "[]"),
            valid.replace("\"items\":", "\"missingItems\":"),
            valid.replace("\"orderId\":42", "\"orderId\":42,\"orderId\":43"));
    }

    @DisplayName("null payload와 null 품목은 거절한다.")
    @Test
    void rejectsNullPayloadAndItem() {
        assertThatThrownBy(() -> parser.parseAndValidate("42", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parseAndValidate("42",
            bytes(validJson().replace("[{\"productId\":7,\"quantity\":2}]", "[null]"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static String validJson() {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"event-42",
             "orderId":42,"confirmedAt":"2026-09-07T01:00:00Z","items":[{"productId":7,"quantity":2}]}
            """;
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
