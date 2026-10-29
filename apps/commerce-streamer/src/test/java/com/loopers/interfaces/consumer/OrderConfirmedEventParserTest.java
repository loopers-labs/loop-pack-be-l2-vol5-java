package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.purchase.OrderConfirmedEvent;
import com.loopers.domain.purchase.PermanentEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 과제 p.2 "주문 42의 확정 이벤트 예시" JSON 을 공통 샘플로 쓴다 (설계 D9). key 는 "42" 다.
 */
class OrderConfirmedEventParserTest {

    static final String ORDER_42_JSON = """
        {
          "eventType": "OrderConfirmed",
          "schemaVersion": 1,
          "eventId": "order-confirmed:v1:42",
          "orderId": 42,
          "confirmedAt": "2026-09-07T01:00:00Z",
          "items": [
            {"productId": 7, "quantity": 2},
            {"productId": 8, "quantity": 1}
          ]
        }
        """;

    private final OrderConfirmedEventParser parser = new OrderConfirmedEventParser(new ObjectMapper());

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @DisplayName("주문 42 샘플을 파싱하면, ")
    @Nested
    class Parse {
        @DisplayName("eventId·orderId·confirmedAt·items 가 계약대로 읽힌다.")
        @Test
        void parsesOrder42Sample() {
            OrderConfirmedEvent event = parser.parse("42", bytes(ORDER_42_JSON));

            assertThat(event.eventId()).isEqualTo("order-confirmed:v1:42");
            assertThat(event.orderId()).isEqualTo(42L);
            assertThat(event.confirmedAt().toInstant()).isEqualTo(ZonedDateTime.parse("2026-09-07T01:00:00Z").toInstant());
            assertThat(event.items()).containsExactly(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1));
            assertThat(event.fingerprint()).isEqualTo("42|2026-09-07T01:00:00Z|7:2,8:1");
        }

        @DisplayName("속성 순서·items 순서가 바뀐 JSON 도 같은 fingerprint 가 나온다.")
        @Test
        void sameFingerprint_whenPropertyAndItemOrderDiffer() {
            String reordered = """
                {"items": [{"quantity": 1, "productId": 8}, {"quantity": 2, "productId": 7}],
                 "confirmedAt": "2026-09-07T01:00:00Z", "orderId": 42, "eventId": "order-confirmed:v1:42",
                 "schemaVersion": 1, "eventType": "OrderConfirmed"}
                """;

            OrderConfirmedEvent event = parser.parse("42", bytes(reordered));

            assertThat(event.fingerprint()).isEqualTo("42|2026-09-07T01:00:00Z|7:2,8:1");
        }

        @DisplayName("밀리초가 있는 confirmedAt (commerce-api 가 Clock 을 밀리초로 잘라 발행) 도 그대로 읽혀 fingerprint 에 찍힌다.")
        @Test
        void keepsMillis_whenConfirmedAtHasFraction() {
            OrderConfirmedEvent event = parser.parse("42", bytes(ORDER_42_JSON.replace("2026-09-07T01:00:00Z", "2026-09-07T01:00:00.123Z")));

            assertThat(event.confirmedAt().toInstant()).isEqualTo(ZonedDateTime.parse("2026-09-07T01:00:00.123Z").toInstant());
            assertThat(event.fingerprint()).isEqualTo("42|2026-09-07T01:00:00.123Z|7:2,8:1");
        }
    }

    @DisplayName("계약을 어기면 영구 오류다: ")
    @Nested
    class Reject {
        @DisplayName("key 가 orderId 와 다르다.")
        @Test
        void keyMismatch() {
            assertThatThrownBy(() -> parser.parse("43", bytes(ORDER_42_JSON)))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("key=43")
                .hasMessageContaining("orderId=42");
        }

        @DisplayName("JSON 이 깨져 있다.")
        @Test
        void brokenJson() {
            assertThatThrownBy(() -> parser.parse("42", bytes("{\"eventType\": ")))
                .isInstanceOf(PermanentEventException.class);
        }

        @DisplayName("value 가 없다 (tombstone).")
        @Test
        void nullValue() {
            assertThatThrownBy(() -> parser.parse("42", null)).isInstanceOf(PermanentEventException.class);
        }

        @DisplayName("eventType 이 OrderConfirmed 가 아니다.")
        @Test
        void wrongEventType() {
            assertThatThrownBy(() -> parser.parse("42", bytes(ORDER_42_JSON.replace("\"OrderConfirmed\"", "\"OrderCancelled\""))))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("OrderCancelled");
        }

        @DisplayName("schemaVersion 이 1 이 아니다.")
        @Test
        void wrongSchemaVersion() {
            assertThatThrownBy(() -> parser.parse("42", bytes(ORDER_42_JSON.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("2");
        }

        @DisplayName("수량이 0 이다.")
        @Test
        void zeroQuantity() {
            assertThatThrownBy(() -> parser.parse("42", bytes(ORDER_42_JSON.replace("\"quantity\": 1", "\"quantity\": 0"))))
                .isInstanceOf(PermanentEventException.class);
        }

        @DisplayName("confirmedAt 형식이 잘못됐다.")
        @Test
        void malformedConfirmedAt() {
            assertThatThrownBy(() -> parser.parse("42", bytes(ORDER_42_JSON.replace("2026-09-07T01:00:00Z", "2026-09-07 01:00"))))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("confirmedAt");
        }

        @DisplayName("items 가 없다.")
        @Test
        void missingItems() {
            String noItems = """
                {"eventType": "OrderConfirmed", "schemaVersion": 1, "eventId": "order-confirmed:v1:42",
                 "orderId": 42, "confirmedAt": "2026-09-07T01:00:00Z"}
                """;

            assertThatThrownBy(() -> parser.parse("42", bytes(noItems)))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("items");
        }

        @DisplayName("orderId 가 정수가 아니다.")
        @Test
        void nonNumericOrderId() {
            assertThatThrownBy(() -> parser.parse("42", bytes(ORDER_42_JSON.replace("\"orderId\": 42", "\"orderId\": \"42\""))))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("orderId");
        }
    }

    @DisplayName("정규화 결과는 List.copyOf 로 고정돼 바꿀 수 없다.")
    @Test
    void itemsAreImmutable() {
        OrderConfirmedEvent event = parser.parse("42", bytes(ORDER_42_JSON));
        List<OrderConfirmedEvent.Item> items = event.items();

        assertThatThrownBy(() -> items.add(new OrderConfirmedEvent.Item(9L, 1))).isInstanceOf(UnsupportedOperationException.class);
    }
}
