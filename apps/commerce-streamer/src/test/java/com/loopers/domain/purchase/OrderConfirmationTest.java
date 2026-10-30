package com.loopers.domain.purchase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 이벤트 안의 약속(docs/week6/decisions.md D-19)을 약속 하나당 하나씩 확인한다.
 */
class OrderConfirmationTest {

    private static final String KEY = "42";

    private static OrderConfirmation.Draft valid() {
        return new OrderConfirmation.Draft("OrderConfirmed", 1, "order-confirmed:v1:42", 42L,
            "2026-09-07T01:00:00.123456Z",
            List.of(new OrderConfirmation.Draft.Item(8L, 1), new OrderConfirmation.Draft.Item(7L, 2)));
    }

    private static OrderConfirmation.Draft withItems(List<OrderConfirmation.Draft.Item> items) {
        OrderConfirmation.Draft d = valid();
        return new OrderConfirmation.Draft(d.eventType(), d.schemaVersion(), d.eventId(), d.orderId(), d.confirmedAt(), items);
    }

    private static String violation(String key, OrderConfirmation.Draft draft) {
        return assertThrows(OrderConfirmationViolation.class, () -> OrderConfirmation.from(key, draft)).reason();
    }

    @DisplayName("약속을 모두 지키면, items를 productId 순으로, 시각을 UTC로 담아 만든다.")
    @Test
    void createsFromValidDraft() {
        // act
        OrderConfirmation confirmation = OrderConfirmation.from(KEY, valid());

        // assert
        assertThat(confirmation.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(confirmation.orderId()).isEqualTo(42L);
        assertThat(confirmation.confirmedAt()).isEqualTo(ZonedDateTime.of(2026, 9, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC));
        assertThat(confirmation.items()).containsExactly(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1));
    }

    @DisplayName("형식 약속: ")
    @Nested
    class Format {
        @DisplayName("eventType이 OrderConfirmed가 아니면 계약 깨짐이다.")
        @Test
        void rejectsOtherEventType() {
            OrderConfirmation.Draft d = valid();
            assertThat(violation(KEY, new OrderConfirmation.Draft("OrderCancelled", d.schemaVersion(), d.eventId(), d.orderId(),
                d.confirmedAt(), d.items()))).contains("eventType");
        }

        @DisplayName("schemaVersion이 1이 아니면 계약 깨짐이다.")
        @Test
        void rejectsOtherSchemaVersion() {
            OrderConfirmation.Draft d = valid();
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 2, d.eventId(), d.orderId(),
                d.confirmedAt(), d.items()))).contains("schemaVersion");
        }

        @DisplayName("필수 필드(eventId·orderId·confirmedAt·items)가 없으면 계약 깨짐이다.")
        @Test
        void rejectsMissingFields() {
            OrderConfirmation.Draft d = valid();
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 1, null, d.orderId(), d.confirmedAt(), d.items())))
                .contains("eventId");
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 1, d.eventId(), null, d.confirmedAt(), d.items())))
                .contains("orderId");
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 1, d.eventId(), d.orderId(), null, d.items())))
                .contains("confirmedAt");
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 1, d.eventId(), d.orderId(), d.confirmedAt(), null)))
                .contains("items");
        }

        @DisplayName("confirmedAt을 시각으로 읽을 수 없거나 마이크로초보다 잘게 쪼개져 있으면 계약 깨짐이다.")
        @Test
        void rejectsBadConfirmedAt() {
            OrderConfirmation.Draft d = valid();
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 1, d.eventId(), d.orderId(), "어제", d.items())))
                .contains("confirmedAt");
            assertThat(violation(KEY, new OrderConfirmation.Draft(d.eventType(), 1, d.eventId(), d.orderId(),
                "2026-09-07T01:00:00.1234567Z", d.items()))).contains("마이크로초");
        }
    }

    @DisplayName("식별 관계 약속: ")
    @Nested
    class Identity {
        @DisplayName("record key가 orderId의 문자열과 다르면 계약 깨짐이다.")
        @Test
        void rejectsKeyMismatch() {
            assertThat(violation("43", valid())).contains("key");
            assertThat(violation(null, valid())).contains("key");
        }

        @DisplayName("eventId가 식별 규칙과 달라도 계약 깨짐이 아니다. 같은 주문의 다른 eventId는 처리 기록과 비교해 conflict로 드러낸다(D-18).")
        @Test
        void acceptsEventIdOfOtherRule() {
            OrderConfirmation.Draft d = valid();
            OrderConfirmation confirmation = OrderConfirmation.from(KEY, new OrderConfirmation.Draft(d.eventType(), 1,
                "order-confirmed:v2:42", d.orderId(), d.confirmedAt(), d.items()));
            assertThat(confirmation.eventId()).isEqualTo("order-confirmed:v2:42");
        }
    }

    @DisplayName("items 약속: ")
    @Nested
    class Items {
        @DisplayName("비어 있으면 계약 깨짐이다.")
        @Test
        void rejectsEmptyItems() {
            assertThat(violation(KEY, withItems(List.of()))).contains("비어");
        }

        @DisplayName("같은 productId가 두 줄이면 계약 깨짐이다.")
        @Test
        void rejectsDuplicateProductId() {
            assertThat(violation(KEY, withItems(List.of(
                new OrderConfirmation.Draft.Item(7L, 2), new OrderConfirmation.Draft.Item(7L, 1))))).contains("중복");
        }

        @DisplayName("수량이 1보다 작으면 계약 깨짐이다.")
        @Test
        void rejectsNonPositiveQuantity() {
            assertThat(violation(KEY, withItems(List.of(new OrderConfirmation.Draft.Item(7L, 0))))).contains("수량");
            assertThat(violation(KEY, withItems(List.of(new OrderConfirmation.Draft.Item(7L, -1))))).contains("수량");
        }

        @DisplayName("품목의 productId나 quantity가 없으면 계약 깨짐이다.")
        @Test
        void rejectsItemWithoutFields() {
            List<OrderConfirmation.Draft.Item> withNull = new ArrayList<>();
            withNull.add(null);
            assertThat(violation(KEY, withItems(List.of(new OrderConfirmation.Draft.Item(null, 1))))).contains("품목");
            assertThat(violation(KEY, withItems(List.of(new OrderConfirmation.Draft.Item(7L, null))))).contains("품목");
            assertThat(violation(KEY, withItems(withNull))).contains("품목");
        }
    }
}
