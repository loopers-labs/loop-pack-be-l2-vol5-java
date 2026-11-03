package com.loopers.domain.purchase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 과제 p.2 주문 42 예시(상품 7 × 2, 상품 8 × 1, 2026-09-07T01:00:00Z)를 기준으로 정규화·fingerprint 규칙을 확인한다.
 */
class OrderConfirmedEventTest {

    private static final String EVENT_ID = "order-confirmed:v1:42";
    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00Z");
    private static final String EXPECTED_FINGERPRINT = "42|2026-09-07T01:00:00Z|7:2,8:1";

    @DisplayName("fingerprint 는, ")
    @Nested
    class Fingerprint {
        @DisplayName("orderId|confirmedAt(UTC)|productId:quantity,... 형식이다.")
        @Test
        void formatsAsOrderIdConfirmedAtAndItems() {
            OrderConfirmedEvent event = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1)));

            assertThat(event.fingerprint()).isEqualTo(EXPECTED_FINGERPRINT);
        }

        @DisplayName("items 순서가 바뀌어도 같은 값이다.")
        @Test
        void isSame_whenItemOrderDiffers() {
            OrderConfirmedEvent reversed = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(8L, 1), new OrderConfirmedEvent.Item(7L, 2)));

            assertThat(reversed.fingerprint()).isEqualTo(EXPECTED_FINGERPRINT);
        }

        @DisplayName("같은 상품이 여러 줄이면 수량을 합산해 하나로 본다.")
        @Test
        void mergesDuplicateProducts() {
            OrderConfirmedEvent split = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(7L, 1), new OrderConfirmedEvent.Item(8L, 1), new OrderConfirmedEvent.Item(7L, 1)));

            assertThat(split.items()).containsExactly(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1));
            assertThat(split.fingerprint()).isEqualTo(EXPECTED_FINGERPRINT);
        }

        @DisplayName("confirmedAt 이 다른 시간대로 표현돼도 같은 순간이면 같은 값이다.")
        @Test
        void isSame_whenConfirmedAtZoneDiffers() {
            OrderConfirmedEvent seoul = new OrderConfirmedEvent(EVENT_ID, 42L, ZonedDateTime.parse("2026-09-07T10:00:00+09:00"),
                List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 1)));

            assertThat(seoul.fingerprint()).isEqualTo(EXPECTED_FINGERPRINT);
        }

        @DisplayName("수량이나 상품이 다르면 다른 값이다.")
        @Test
        void differs_whenQuantityDiffers() {
            OrderConfirmedEvent changed = new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(7L, 3), new OrderConfirmedEvent.Item(8L, 1)));

            assertThat(changed.fingerprint()).isNotEqualTo(EXPECTED_FINGERPRINT);
        }
    }

    @DisplayName("이벤트를 만들 때, ")
    @Nested
    class Create {
        @DisplayName("수량이 0 이하인 품목이 있으면 영구 오류다.")
        @Test
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT,
                List.of(new OrderConfirmedEvent.Item(7L, 2), new OrderConfirmedEvent.Item(8L, 0))))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("quantity");
        }

        @DisplayName("items 가 비어 있으면 영구 오류다.")
        @Test
        void rejectsEmptyItems() {
            assertThatThrownBy(() -> new OrderConfirmedEvent(EVENT_ID, 42L, CONFIRMED_AT, List.of()))
                .isInstanceOf(PermanentEventException.class);
        }

        @DisplayName("eventId·orderId·confirmedAt 이 없으면 영구 오류다.")
        @Test
        void rejectsMissingRequiredFields() {
            List<OrderConfirmedEvent.Item> items = List.of(new OrderConfirmedEvent.Item(7L, 2));

            assertThatThrownBy(() -> new OrderConfirmedEvent(" ", 42L, CONFIRMED_AT, items)).isInstanceOf(PermanentEventException.class);
            assertThatThrownBy(() -> new OrderConfirmedEvent(EVENT_ID, null, CONFIRMED_AT, items)).isInstanceOf(PermanentEventException.class);
            assertThatThrownBy(() -> new OrderConfirmedEvent(EVENT_ID, 42L, null, items)).isInstanceOf(PermanentEventException.class);
        }
    }
}
