package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.InvalidOrderEventException;
import com.loopers.domain.purchase.OrderConfirmation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedParserTest {

    private final OrderConfirmedParser parser = new OrderConfirmedParser(new ObjectMapper());

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
         "confirmedAt":"2026-09-07T01:00:00Z","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
        """;

    @DisplayName("계약대로의 이벤트(주문 42)는 확정 사실로 읽힌다.")
    @Test
    void parsesContractExample() {
        // act
        OrderConfirmation confirmation = parser.parseAndValidate("42", json(VALID));

        // assert
        assertThat(confirmation.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(confirmation.orderId()).isEqualTo(42L);
        assertThat(confirmation.confirmedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00Z"));
        assertThat(confirmation.items()).containsExactly(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1));
    }

    @DisplayName("속성 순서·items 순서가 달라도, 같은 상품이 나뉘어 와도 같은 사실(같은 fingerprint)이다.")
    @Test
    void sameFactHasSameFingerprint_regardlessOfOrderAndSplitting() {
        // arrange
        byte[] reordered = json("""
            {"items":[{"quantity":1,"productId":8},{"quantity":1,"productId":7},{"quantity":1,"productId":7}],
             "confirmedAt":"2026-09-07T01:00:00Z","orderId":42,"eventId":"order-confirmed:v1:42","schemaVersion":1,"eventType":"OrderConfirmed"}
            """);

        // act
        OrderConfirmation original = parser.parseAndValidate("42", json(VALID));
        OrderConfirmation other = parser.parseAndValidate("42", reordered);

        // assert
        assertThat(other.items()).isEqualTo(original.items());
        assertThat(other.fingerprint()).isEqualTo(original.fingerprint());
    }

    @DisplayName("수량이나 확정 시각이 다르면 다른 사실이다.")
    @Test
    void differentFactHasDifferentFingerprint() {
        // act
        OrderConfirmation original = parser.parseAndValidate("42", json(VALID));
        OrderConfirmation otherQuantity = parser.parseAndValidate("42", json(VALID.replace("\"quantity\":2", "\"quantity\":3")));
        OrderConfirmation otherTime = parser.parseAndValidate("42", json(VALID.replace("01:00:00Z", "01:00:01Z")));

        // assert
        assertThat(otherQuantity.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(otherTime.fingerprint()).isNotEqualTo(original.fingerprint());
    }

    @DisplayName("계약을 지키지 않은 이벤트는 건너뛰지 않고 오류로 드러낸다.")
    @Test
    void rejectsContractViolations() {
        assertInvalid("43", VALID); // key와 orderId 불일치
        assertInvalid(null, VALID);
        assertInvalid("42", VALID.replace("OrderConfirmed", "OrderCancelled"));
        assertInvalid("42", VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertInvalid("42", VALID.replace("\"quantity\":2", "\"quantity\":0"));
        assertInvalid("42", VALID.replace("\"productId\":7", "\"productId\":-7"));
        assertInvalid("42", VALID.replace("\"eventId\":\"order-confirmed:v1:42\"", "\"eventId\":\" \""));
        assertInvalid("42", VALID.replace("2026-09-07T01:00:00Z", "어제"));
        assertInvalid("42", VALID.replace("\"orderId\":42", "\"orderId\":\"42\""));
        assertInvalid("42", "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"eventId\":\"e\",\"orderId\":42,"
            + "\"confirmedAt\":\"2026-09-07T01:00:00Z\",\"items\":[]}");
        assertInvalid("42", "이건 JSON이 아니다");
        assertInvalid("42", "[1,2,3]");
        assertThatThrownBy(() -> parser.parseAndValidate("42", null)).isInstanceOf(InvalidOrderEventException.class);
    }

    private void assertInvalid(String key, String body) {
        assertThatThrownBy(() -> parser.parseAndValidate(key, json(body)))
            .as("key=%s body=%s", key, body)
            .isInstanceOf(InvalidOrderEventException.class);
    }

    @DisplayName("파싱한 값을 그대로 목록으로 비교할 수 있도록, 합산된 품목은 불변 목록이다.")
    @Test
    void itemsAreImmutable() {
        // act
        List<OrderConfirmation.Item> items = parser.parseAndValidate("42", json(VALID)).items();

        // assert
        assertThatThrownBy(() -> items.add(new OrderConfirmation.Item(9L, 1))).isInstanceOf(UnsupportedOperationException.class);
    }
}
