package com.loopers.interfaces.consumer.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.interfaces.consumer.ordering.dto.OrderConfirmedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class OrderConfirmedContractTest {
    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser();

    @Test
    @DisplayName("공통 JSON 계약을 읽고 순서와 시간대 및 추가 필드가 달라도 같은 구매 사실로 정규화한다")
    void normalizesTheSharedContract() throws Exception {
        var expected = parse("42", fixture());
        var alternative = """
            {"items":[{"productId":8,"quantity":1},{"productId":7,"quantity":1,"label":"ignored"},
                      {"productId":7,"quantity":1}],
             "eventId":"e321d56a-59b3-4cf1-a7d0-6238451af280",
             "confirmedAt":"2026-11-04T10:00:00.123456+09:00","orderId":42,
             "schemaVersion":1,"eventType":"OrderConfirmed","extra":true}
            """;

        assertThat(expected.eventId()).isEqualTo(UUID.fromString("e321d56a-59b3-4cf1-a7d0-6238451af280"));
        assertThat(expected.orderId()).isEqualTo(42);
        assertThat(expected.confirmedAt()).isEqualTo(Instant.parse("2026-11-04T01:00:00.123456Z"));
        assertThat(expected.items()).containsExactly(
            new OrderConfirmedMessage.Item(7, 2), new OrderConfirmedMessage.Item(8, 1));
        assertThat(parse("42", alternative)).isEqualTo(expected);
        assertThatThrownBy(() -> expected.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"42\"", "42.0", "0", "9223372036854775808", "42,\"orderId\":42"})
    @DisplayName("주문 ID의 잘못된 타입, 범위 및 중복 필드를 거절한다")
    void rejectsInvalidOrderIds(String invalid) throws Exception {
        var json = fixture().replace("\"orderId\": 42", "\"orderId\": " + invalid);
        assertThatThrownBy(() -> parse("42", json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키 불일치, 미지원 버전, 정밀도 초과 및 품목 합산 범위 초과를 거절한다")
    void rejectsInvalidContracts() throws Exception {
        var json = fixture();
        assertThatThrownBy(() -> parse("042", json)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("42", json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("42", json.replace(".123456Z", ".1234567Z")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("42", json.replace("\"productId\": 8, \"quantity\": 1",
            "\"productId\": 7, \"quantity\": 2147483647"))).isInstanceOf(IllegalArgumentException.class);
    }

    private OrderConfirmedMessage parse(String key, String json) {
        return parser.parse(key, json.getBytes(StandardCharsets.UTF_8));
    }

    private String fixture() throws Exception {
        return new ClassPathResource("order-confirmed-v1.json").getContentAsString(StandardCharsets.UTF_8);
    }
}
