package com.loopers.order.domain;

import com.loopers.common.domain.Money;
import com.loopers.product.domain.ProductSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W6 D-2 이벤트 계약 — 확정 주문의 저장된 사실(품목·수량·확정 시각)에서 OrderConfirmed를 만든다.
 */
class OrderConfirmedTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.of(2026, 10, 28, 14, 3, 12, 123_456_000, ZoneId.of("Asia/Seoul"));
    private static final Map<Long, ProductSnapshot> SNAPSHOTS = Map.of(
        7L, new ProductSnapshot(7L, "에어맥스", Money.of(1_000)),
        8L, new ProductSnapshot(8L, "에어포스", Money.of(2_000))
    );

    /** 상품 8을 먼저, 7을 나중에 담은 주문 42 — items 정렬을 보려고 일부러 역순이다. */
    private static OrderModel order42() {
        OrderModel order = OrderModel.create(1L, OrderLines.of(List.of(
            new OrderLines.Line(8L, 1),
            new OrderLines.Line(7L, 2)
        )), SNAPSHOTS);
        ReflectionTestUtils.setField(order, "id", 42L);
        return order;
    }

    private static OrderModel confirmed(ZonedDateTime confirmedAt) {
        OrderModel order = order42();
        order.startPayment();
        order.confirmPaid(confirmedAt);
        return order;
    }

    @DisplayName("확정 주문에서 만들 때, ")
    @Nested
    class FromConfirmedOrder {

        @DisplayName("eventId는 받은 값, eventType OrderConfirmed · schemaVersion 1, orderId는 주문 ID다.")
        @Test
        void carriesIdentity() {
            OrderConfirmed event = OrderConfirmed.from("evt-1", confirmed(CONFIRMED_AT));

            assertThat(event.eventId()).isEqualTo("evt-1");
            assertThat(event.eventType()).isEqualTo("OrderConfirmed");
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.orderId()).isEqualTo(42L);
        }

        @DisplayName("items는 상품 ID 오름차순 [(7, 2), (8, 1)]이다 — 담은 순서(8 → 7)와 무관하다.")
        @Test
        void sortsItemsByProductId() {
            OrderConfirmed event = OrderConfirmed.from("evt-1", confirmed(CONFIRMED_AT));

            assertThat(event.items()).containsExactly(
                new OrderConfirmed.Item(7L, 2),
                new OrderConfirmed.Item(8L, 1)
            );
        }

        @DisplayName("confirmedAt은 주문의 확정 시각을 마이크로초 6자리와 offset으로 쓴다 — 2026-10-28T14:03:12.123456+09:00")
        @Test
        void formatsConfirmedAtWithMicros() {
            OrderConfirmed event = OrderConfirmed.from("evt-1", confirmed(CONFIRMED_AT));

            assertThat(event.confirmedAt()).isEqualTo("2026-10-28T14:03:12.123456+09:00");
        }

        @DisplayName("확정 시각의 소수부가 0이어도 6자리를 쓴다 — 2026-10-28T14:03:12.000000+09:00 (형식이 값마다 달라지지 않게)")
        @Test
        void keepsSixDigitsWhenFractionIsZero() {
            OrderConfirmed event = OrderConfirmed.from("evt-1", confirmed(CONFIRMED_AT.withNano(0)));

            assertThat(event.confirmedAt()).isEqualTo("2026-10-28T14:03:12.000000+09:00");
        }

        @DisplayName("C-3 이벤트에는 eventId·eventType·schemaVersion·orderId·confirmedAt·items만, 품목에는 productId·quantity만 있다 (가격·상품명·사용자 없음).")
        @Test
        void carriesNoSensitiveOrExtraFields() {
            assertThat(componentNames(OrderConfirmed.class))
                .containsExactly("eventId", "eventType", "schemaVersion", "orderId", "confirmedAt", "items");
            assertThat(componentNames(OrderConfirmed.Item.class))
                .containsExactly("productId", "quantity");
        }
    }

    @DisplayName("확정이 아닌 주문에서 만들 때, ")
    @Nested
    class FromUnconfirmedOrder {

        @DisplayName("DRAFT 주문이면 만들지 않는다.")
        @Test
        void rejectsDraft() {
            assertThatThrownBy(() -> OrderConfirmed.from("evt-1", order42()))
                .isInstanceOf(IllegalStateException.class);
        }

        @DisplayName("PAYMENT_PENDING 주문이면 만들지 않는다.")
        @Test
        void rejectsPaymentPending() {
            OrderModel order = order42();
            order.startPayment();

            assertThatThrownBy(() -> OrderConfirmed.from("evt-1", order))
                .isInstanceOf(IllegalStateException.class);
        }

        @DisplayName("PAYMENT_FAILED 주문이면 만들지 않는다.")
        @Test
        void rejectsPaymentFailed() {
            OrderModel order = order42();
            order.startPayment();
            order.failPayment();

            assertThatThrownBy(() -> OrderConfirmed.from("evt-1", order))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    private static List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
