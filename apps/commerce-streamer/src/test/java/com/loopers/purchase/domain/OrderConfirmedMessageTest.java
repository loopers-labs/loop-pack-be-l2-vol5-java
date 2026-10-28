package com.loopers.purchase.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W6 D-6 — 받은 OrderConfirmed의 사실을 정규화해 fingerprint로 비교한다.
 * JSON 속성·items 순서, 같은 순간의 다른 offset 표기는 같은 사실이다. 수량·시각·주문이 다르면 다른 사실이다.
 */
class OrderConfirmedMessageTest {

    private static final String AT_SEOUL = "2026-10-28T14:03:12.123456+09:00";
    private static final String AT_UTC = "2026-10-28T05:03:12.123456Z";

    private static OrderConfirmedMessage message(String eventId, Long orderId, String confirmedAt, List<OrderConfirmedMessage.Item> items) {
        return new OrderConfirmedMessage(eventId, "OrderConfirmed", 1, orderId, confirmedAt, items);
    }

    private static OrderConfirmedMessage.Item item(long productId, int quantity) {
        return new OrderConfirmedMessage.Item(productId, quantity);
    }

    @DisplayName("fingerprint를 만들 때, ")
    @Nested
    class Fingerprint {

        @DisplayName("items 순서가 달라도([7×2, 8×1] / [8×1, 7×2]) 같다.")
        @Test
        void ignoresItemOrder() {
            String a = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2), item(8, 1))).fingerprint();
            String b = message("evt-1", 42L, AT_SEOUL, List.of(item(8, 1), item(7, 2))).fingerprint();

            assertThat(a).isNotBlank().isEqualTo(b);
        }

        @DisplayName("같은 순간을 다른 offset으로 써도(+09:00 / Z) 같다.")
        @Test
        void ignoresOffsetNotation() {
            String a = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2))).fingerprint();
            String b = message("evt-1", 42L, AT_UTC, List.of(item(7, 2))).fingerprint();

            assertThat(a).isNotBlank().isEqualTo(b);
        }

        @DisplayName("같은 상품이 두 줄로 와도([7×1, 7×1, 8×1]) 합친 [7×2, 8×1]과 같다.")
        @Test
        void mergesDuplicateProducts() {
            String split = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 1), item(7, 1), item(8, 1))).fingerprint();
            String merged = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2), item(8, 1))).fingerprint();

            assertThat(split).isNotBlank().isEqualTo(merged);
        }

        @DisplayName("eventId는 사실이 아니다 — eventId만 달라도 같다 (같은 주문·다른 eventId는 판정에서 따로 잡는다).")
        @Test
        void excludesEventId() {
            String a = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2))).fingerprint();
            String b = message("evt-2", 42L, AT_SEOUL, List.of(item(7, 2))).fingerprint();

            assertThat(a).isNotBlank().isEqualTo(b);
        }

        @DisplayName("수량이 다르면(7×2 / 7×3) 다르다.")
        @Test
        void differsByQuantity() {
            String a = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2))).fingerprint();
            String b = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 3))).fingerprint();

            assertThat(a).isNotEqualTo(b);
        }

        @DisplayName("확정 시각이 1µs만 달라도 다르다.")
        @Test
        void differsByConfirmedAt() {
            String a = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2))).fingerprint();
            String b = message("evt-1", 42L, "2026-10-28T14:03:12.123457+09:00", List.of(item(7, 2))).fingerprint();

            assertThat(a).isNotEqualTo(b);
        }

        @DisplayName("주문이 다르면(42 / 43) 다르다.")
        @Test
        void differsByOrder() {
            String a = message("evt-1", 42L, AT_SEOUL, List.of(item(7, 2))).fingerprint();
            String b = message("evt-1", 43L, AT_SEOUL, List.of(item(7, 2))).fingerprint();

            assertThat(a).isNotEqualTo(b);
        }

        @DisplayName("저장할 품목은 상품 ID 오름차순으로 합친 [7×2, 8×1]이다.")
        @Test
        void normalizesItemsForStorage() {
            OrderConfirmedMessage message = message("evt-1", 42L, AT_SEOUL, List.of(item(8, 1), item(7, 1), item(7, 1)));

            assertThat(message.normalizedItems()).containsExactly(item(7, 2), item(8, 1));
        }
    }

    @DisplayName("계약에 맞지 않는 이벤트면, ")
    @Nested
    class Invalid {

        @DisplayName("수량이 0이면 거절한다.")
        @Test
        void rejectsZeroQuantity() {
            assertThatThrownBy(() -> message("evt-1", 42L, AT_SEOUL, List.of(item(7, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("items가 비어 있으면 거절한다.")
        @Test
        void rejectsEmptyItems() {
            assertThatThrownBy(() -> message("evt-1", 42L, AT_SEOUL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("eventType이 OrderConfirmed가 아니거나 schemaVersion이 1이 아니면 거절한다.")
        @Test
        void rejectsUnsupportedTypeOrVersion() {
            assertThatThrownBy(() -> new OrderConfirmedMessage("evt-1", "OrderCanceled", 1, 42L, AT_SEOUL, List.of(item(7, 2))))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OrderConfirmedMessage("evt-1", "OrderConfirmed", 2, 42L, AT_SEOUL, List.of(item(7, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("eventId·orderId·confirmedAt이 없으면 거절한다.")
        @Test
        void rejectsMissingIdentity() {
            assertThatThrownBy(() -> message(null, 42L, AT_SEOUL, List.of(item(7, 2)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> message("evt-1", null, AT_SEOUL, List.of(item(7, 2)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> message("evt-1", 42L, null, List.of(item(7, 2)))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
