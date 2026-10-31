package com.loopers.domain.purchase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmedPurchaseTest {

    private static final String EVENT_ID = "order-confirmed:v1:42";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-30T01:02:03.120000Z");

    private static PurchaseLine line(Long productId, int quantity) {
        return new PurchaseLine(productId, quantity);
    }

    private static PurchaseReading read(String key, String eventId, Long orderId, Instant confirmedAt,
                                        List<PurchaseLine> items) {
        return ConfirmedPurchase.read(key, eventId, orderId, confirmedAt, items);
    }

    private static ConfirmedPurchase readable(List<PurchaseLine> items) {
        PurchaseReading reading = read("42", EVENT_ID, 42L, CONFIRMED_AT, items);
        assertThat(reading).isInstanceOf(PurchaseReading.Readable.class);
        return ((PurchaseReading.Readable) reading).purchase();
    }

    private static UnreadableReason unreadable(PurchaseReading reading) {
        assertThat(reading).isInstanceOf(PurchaseReading.Unreadable.class);
        return ((PurchaseReading.Unreadable) reading).reason();
    }

    @Nested
    @DisplayName("ACT-002, ACT-006: 확정 사실은 품목을 상품 id 순서로 정규화해 든다")
    class Normalize {

        @DisplayName("ACT-013: 주문 42 가 상품 8 을 1 개, 상품 7 을 2 개 사면 품목은 7, 8 순서이고 비교 값은 7:2,8:1 이다")
        @Test
        void ordersItemsByProduct() {
            ConfirmedPurchase purchase = readable(List.of(line(8L, 1), line(7L, 2)));

            assertThat(purchase.eventId()).isEqualTo(EVENT_ID);
            assertThat(purchase.orderId()).isEqualTo(42L);
            assertThat(purchase.confirmedAt()).isEqualTo(CONFIRMED_AT);
            assertThat(purchase.items()).containsExactly(line(7L, 2), line(8L, 1));
            assertThat(purchase.itemsDigest()).isEqualTo("7:2,8:1");
        }

        @DisplayName("ACT-006: 확정 시각은 저장할 수 있는 마이크로초까지만 든다. 생산자가 자르지 않아도 다시 읽은 기록과 같은 값이 된다")
        @Test
        void keepsConfirmedAtToMicros() {
            PurchaseReading reading = read("42", EVENT_ID, 42L, Instant.parse("2026-10-30T01:02:03.1234567Z"),
                List.of(line(7L, 2)));

            assertThat(((PurchaseReading.Readable) reading).purchase().confirmedAt())
                .isEqualTo(Instant.parse("2026-10-30T01:02:03.123456Z"));
        }

        @DisplayName("ACT-009: eventId 의 형식은 보지 않는다. 다른 형식이어도 읽힌다")
        @Test
        void ignoresEventIdFormat() {
            PurchaseReading reading = read("42", "something-else", 42L, CONFIRMED_AT, List.of(line(7L, 2)));

            assertThat(reading).isInstanceOf(PurchaseReading.Readable.class);
        }
    }

    @Nested
    @DisplayName("ACT-004, ACT-005, ACT-006: 같은 사실은 주문, 확정 시각, 정규화한 품목이 모두 같은 것이다")
    class SameFact {

        private final ConfirmedPurchase stored = readable(List.of(line(7L, 2), line(8L, 1)));

        @DisplayName("ACT-006: 품목 순서만 다른 재발행은 같은 사실이다")
        @Test
        void sameWhenOnlyItemOrderDiffers() {
            ConfirmedPurchase reordered = readable(List.of(line(8L, 1), line(7L, 2)));

            assertThat(reordered.isSameFactAs(stored.orderId(), stored.confirmedAt(), stored.itemsDigest())).isTrue();
        }

        @DisplayName("ACT-005: 수량이 다르면 다른 사실이다")
        @Test
        void differentWhenQuantityDiffers() {
            ConfirmedPurchase changed = readable(List.of(line(7L, 3), line(8L, 1)));

            assertThat(changed.isSameFactAs(stored.orderId(), stored.confirmedAt(), stored.itemsDigest())).isFalse();
        }

        @DisplayName("ACT-005: 확정 시각이 다르면 다른 사실이다")
        @Test
        void differentWhenConfirmedAtDiffers() {
            assertThat(stored.isSameFactAs(stored.orderId(), CONFIRMED_AT.plusNanos(1_000), stored.itemsDigest()))
                .isFalse();
        }

        @DisplayName("ACT-005: 주문이 다르면 다른 사실이다")
        @Test
        void differentWhenOrderDiffers() {
            assertThat(stored.isSameFactAs(43L, stored.confirmedAt(), stored.itemsDigest())).isFalse();
        }
    }

    @Nested
    @DisplayName("ACT-009: 다시 읽어도 결과가 같은 record 는 읽을 수 없음으로 판정한다")
    class Unreadable {

        @DisplayName("key 와 orderId 가 다르면 읽을 수 없다")
        @Test
        void keyMismatch() {
            assertThat(unreadable(read("43", EVENT_ID, 42L, CONFIRMED_AT, List.of(line(7L, 2)))))
                .isEqualTo(UnreadableReason.KEY_MISMATCH);
        }

        @DisplayName("eventId 가 비었거나 없으면 읽을 수 없다")
        @Test
        void blankEventId() {
            assertThat(unreadable(read("42", " ", 42L, CONFIRMED_AT, List.of(line(7L, 2)))))
                .isEqualTo(UnreadableReason.BLANK_EVENT_ID);
            assertThat(unreadable(read("42", null, 42L, CONFIRMED_AT, List.of(line(7L, 2)))))
                .isEqualTo(UnreadableReason.BLANK_EVENT_ID);
        }

        @DisplayName("orderId 나 확정 시각이 없으면 읽을 수 없다")
        @Test
        void missingFields() {
            assertThat(unreadable(read("42", EVENT_ID, null, CONFIRMED_AT, List.of(line(7L, 2)))))
                .isEqualTo(UnreadableReason.MISSING_ORDER_ID);
            assertThat(unreadable(read("42", EVENT_ID, 42L, null, List.of(line(7L, 2)))))
                .isEqualTo(UnreadableReason.MISSING_CONFIRMED_AT);
        }

        @DisplayName("품목이 비었거나 없으면 읽을 수 없다")
        @Test
        void emptyItems() {
            assertThat(unreadable(read("42", EVENT_ID, 42L, CONFIRMED_AT, List.of())))
                .isEqualTo(UnreadableReason.EMPTY_ITEMS);
            assertThat(unreadable(read("42", EVENT_ID, 42L, CONFIRMED_AT, null)))
                .isEqualTo(UnreadableReason.EMPTY_ITEMS);
        }

        @DisplayName("상품 id 가 없는 품목이 있으면 읽을 수 없다")
        @Test
        void missingProductId() {
            assertThat(unreadable(read("42", EVENT_ID, 42L, CONFIRMED_AT, List.of(line(null, 2)))))
                .isEqualTo(UnreadableReason.MISSING_PRODUCT_ID);
        }

        @DisplayName("수량이 0 이하인 품목이 있으면 읽을 수 없다")
        @Test
        void nonPositiveQuantity() {
            assertThat(unreadable(read("42", EVENT_ID, 42L, CONFIRMED_AT, List.of(line(7L, 2), line(8L, 0)))))
                .isEqualTo(UnreadableReason.NON_POSITIVE_QUANTITY);
        }

        @DisplayName("같은 상품이 두 번 나오면 읽을 수 없다. insert 전에 걸러야 비교할 기록 없는 유일 위반이 생기지 않는다")
        @Test
        void duplicateProduct() {
            assertThat(unreadable(read("42", EVENT_ID, 42L, CONFIRMED_AT, List.of(line(7L, 2), line(7L, 1)))))
                .isEqualTo(UnreadableReason.DUPLICATE_PRODUCT);
        }
    }

    @Nested
    @DisplayName("ACT-004, ACT-005, ACT-008: 처리 기록과 부딪혔을 때 이미 있는 기록과 견주어 가른다")
    class Collision {

        private final ConfirmedPurchase incoming = readable(List.of(line(7L, 2), line(8L, 1)));

        private RecordedConfirmation recorded(String eventId, Long orderId, String itemsDigest) {
            return new RecordedConfirmation(eventId, orderId, CONFIRMED_AT, itemsDigest);
        }

        @DisplayName("ACT-004: 같은 eventId 의 같은 사실이 있으면 같은 사실이다")
        @Test
        void sameFact() {
            RecordedConfirmation same = recorded(EVENT_ID, 42L, "7:2,8:1");

            assertThat(incoming.collideWith(Optional.of(same), Optional.of(same)))
                .isEqualTo(ReceiptCollision.SAME_FACT);
        }

        @DisplayName("ACT-005: 같은 eventId 에 다른 사실이 있으면 eventId 충돌이다")
        @Test
        void eventConflict() {
            RecordedConfirmation other = recorded(EVENT_ID, 42L, "7:3,8:1");

            assertThat(incoming.collideWith(Optional.of(other), Optional.of(other)))
                .isEqualTo(ReceiptCollision.EVENT_CONFLICT);
        }

        @DisplayName("ACT-005: 같은 주문에 다른 eventId 의 기록이 있으면 내용이 같아도 주문 충돌이다")
        @Test
        void orderConflict() {
            RecordedConfirmation other = recorded("order-confirmed:v2:42", 42L, "7:2,8:1");

            assertThat(incoming.collideWith(Optional.empty(), Optional.of(other)))
                .isEqualTo(ReceiptCollision.ORDER_CONFLICT);
        }

        @DisplayName("ACT-008: 다시 읽었는데 부딪힌 기록이 없으면 중복이 아니다")
        @Test
        void nothingRecorded() {
            assertThat(incoming.collideWith(Optional.empty(), Optional.empty()))
                .isEqualTo(ReceiptCollision.NOT_RECORDED);
        }
    }
}
