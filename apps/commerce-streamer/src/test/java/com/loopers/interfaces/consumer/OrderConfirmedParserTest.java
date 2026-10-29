package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfirmedParserTest {

    private final OrderConfirmedParser parser = new OrderConfirmedParser(new ObjectMapper());

    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
         "confirmedAt":"2026-09-07T01:00:00.123456Z","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
        """;

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @DisplayName("계약대로 온 메시지를 확정 사실로 바꾼다. 속성 순서가 달라도 같은 값이다.")
    @Test
    void parsesContract() {
        // arrange
        String reordered = """
            {"items":[{"quantity":2,"productId":7},{"quantity":1,"productId":8}],"confirmedAt":"2026-09-07T01:00:00.123456Z",
             "orderId":42,"eventId":"order-confirmed:v1:42","schemaVersion":1,"eventType":"OrderConfirmed"}
            """;

        // act
        OrderConfirmation result = parser.parse("42", bytes(VALID));

        // assert
        assertAll(
            () -> assertThat(result.eventId()).isEqualTo("order-confirmed:v1:42"),
            () -> assertThat(result.orderId()).isEqualTo(42L),
            () -> assertThat(result.confirmedAt().toInstant()).isEqualTo(Instant.parse("2026-09-07T01:00:00.123456Z")),
            () -> assertThat(result.items()).containsExactly(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)),
            () -> assertThat(parser.parse("42", bytes(reordered))).isEqualTo(result)
        );
    }

    static Stream<Arguments> rejectedMessages() {
        return Stream.of(
            Arguments.of("JSON 이 아님", "42", "not-json", RejectReason.INVALID_FORMAT),
            Arguments.of("객체가 아님", "42", "[1,2]", RejectReason.INVALID_FORMAT),
            Arguments.of("필수 필드 누락", "42", VALID.replace("\"eventId\":\"order-confirmed:v1:42\",", ""), RejectReason.INVALID_FORMAT),
            Arguments.of("숫자 자리에 문자", "42", VALID.replace("\"orderId\":42", "\"orderId\":\"42\""), RejectReason.INVALID_FORMAT),
            Arguments.of("시각 형식 오류", "42", VALID.replace("2026-09-07T01:00:00.123456Z", "어제"), RejectReason.INVALID_FORMAT),
            Arguments.of("다른 이벤트 종류", "42", VALID.replace("\"OrderConfirmed\"", "\"OrderCanceled\""), RejectReason.UNSUPPORTED_EVENT),
            Arguments.of("지원하지 않는 버전", "42", VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), RejectReason.UNSUPPORTED_EVENT),
            Arguments.of("key 와 주문 불일치", "43", VALID, RejectReason.KEY_MISMATCH),
            Arguments.of("중복 상품", "42", VALID.replace("\"productId\":8", "\"productId\":7"), RejectReason.DUPLICATE_PRODUCT),
            Arguments.of("마이크로초 초과", "42", VALID.replace(".123456Z", ".123456789Z"), RejectReason.TOO_PRECISE_TIME)
        );
    }

    @DisplayName("계약과 다른 메시지는 사유와 함께 영구 오류로 거절한다. (설계 4.2)")
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedMessages")
    void rejectsBrokenMessage(String scene, String key, String json, RejectReason reason) {
        RejectedEventException result = assertThrows(RejectedEventException.class, () -> parser.parse(key, bytes(json)));

        assertThat(result.getReason()).isEqualTo(reason);
    }

    @DisplayName("본문이 없으면 INVALID_FORMAT 으로 거절한다.")
    @Test
    void rejectsNullValue() {
        RejectedEventException result = assertThrows(RejectedEventException.class, () -> parser.parse("42", null));

        assertAll(
            () -> assertThat(result.getReason()).isEqualTo(RejectReason.INVALID_FORMAT),
            () -> assertThat(result.getMessage()).contains("key=42")
        );
    }
}
