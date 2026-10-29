package com.loopers.domain.purchase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 장부 1행과 새 이벤트를 비교하는 충돌 규칙(설계 D10 표)과 엔티티 생성자의 최소 검증을 확인한다.
 */
class ProcessedOrderConfirmationTest {

    private static final String EVENT_ID = "order-confirmed:v1:42";
    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00Z");
    private static final List<OrderConfirmedEvent.Item> ITEMS =
        List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1));
    private static final OrderConfirmedEvent ORDER_42 = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT, ITEMS);

    private static final ZonedDateTime PROCESSED_AT = ZonedDateTime.parse("2026-09-07T01:00:05Z");

    private final ProcessedOrderConfirmation processed =
        new ProcessedOrderConfirmation(EVENT_ID, 42L, CONFIRMED_AT, ORDER_42.fingerprint(), PROCESSED_AT);

    @DisplayName("장부의 사실과 새 이벤트를 비교할 때, ")
    @Nested
    class VerifySameFact {
        @DisplayName("같은 eventId·같은 fingerprint 면 아무 일도 없다 (no-op).")
        @Test
        void passes_whenSameFact() {
            OrderConfirmedEvent redelivered = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(8L, 1), new OrderConfirmedEvent.Item(7L, 2)));

            assertThatCode(() -> processed.verifySameFact(redelivered)).doesNotThrowAnyException();
        }

        @DisplayName("같은 eventId 인데 수량이 다르면 기존·새 fingerprint 를 담은 충돌이다.")
        @Test
        void conflicts_whenSameEventIdDifferentFingerprint() {
            OrderConfirmedEvent changed = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(7L, 5), new OrderConfirmedEvent.Item(8L, 1)));

            assertThatThrownBy(() -> processed.verifySameFact(changed))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("42|2026-09-07T01:00:00Z|7:2,8:1")
                .hasMessageContaining("42|2026-09-07T01:00:00Z|7:5,8:1");
        }

        @DisplayName("같은 eventId 인데 confirmedAt 이 다르면 충돌이다.")
        @Test
        void conflicts_whenSameEventIdDifferentConfirmedAt() {
            OrderConfirmedEvent changed = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT.plusMinutes(1), ITEMS);

            assertThatThrownBy(() -> processed.verifySameFact(changed)).isInstanceOf(PermanentEventException.class);
        }

        @DisplayName("같은 orderId 인데 eventId 가 다르면 충돌이다.")
        @Test
        void conflicts_whenSameOrderIdDifferentEventId() {
            OrderConfirmedEvent otherEvent = new OrderConfirmedEvent("other-event", 42L, CONFIRMED_AT, ITEMS);

            assertThatThrownBy(() -> processed.verifySameFact(otherEvent))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining(EVENT_ID)
                .hasMessageContaining("other-event");
        }
    }

    @DisplayName("장부 생성자는, ")
    @Nested
    class LedgerGuard {
        @DisplayName("eventId·orderId·confirmedAt·fingerprint·processedAt 이 없으면 거절한다.")
        @Test
        void rejectsMissingValues() {
            assertThatThrownBy(() -> new ProcessedOrderConfirmation(null, 42L, CONFIRMED_AT, "fp", PROCESSED_AT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProcessedOrderConfirmation(EVENT_ID, null, CONFIRMED_AT, "fp", PROCESSED_AT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProcessedOrderConfirmation(EVENT_ID, 42L, null, "fp", PROCESSED_AT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProcessedOrderConfirmation(EVENT_ID, 42L, CONFIRMED_AT, " ", PROCESSED_AT))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProcessedOrderConfirmation(EVENT_ID, 42L, CONFIRMED_AT, "fp", null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("구매 활동 생성자는, ")
    @Nested
    class ActivityGuard {
        @DisplayName("수량이 0 이하면 거절한다.")
        @Test
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> new ProductPurchaseActivity(42L, 7L, 0, CONFIRMED_AT)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("orderId·productId·confirmedAt 이 없으면 거절한다.")
        @Test
        void rejectsMissingValues() {
            assertThatThrownBy(() -> new ProductPurchaseActivity(null, 7L, 1, CONFIRMED_AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProductPurchaseActivity(42L, null, 1, CONFIRMED_AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProductPurchaseActivity(42L, 7L, 1, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
