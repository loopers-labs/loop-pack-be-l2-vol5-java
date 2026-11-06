package com.loopers.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedEventTest {

    private static OrderModel pgConfirmed(List<OrderItem> items) {
        OrderModel order = new OrderModel(1L, items);
        order.startPgPayment();
        order.completePgPayment(order.getTotalAmount());
        return order;
    }

    @DisplayName("확정 시각은, ")
    @Nested
    class ConfirmedAt {

        @DisplayName("PG 성공으로 확정할 때 밀리초 정밀도로 기록되고, 그 외 전이에서는 비어 있다.")
        @Test
        void isRecordedOnlyByPgCompletion() {
            // arrange
            List<OrderItem> items = List.of(new OrderItem(7L, 2, 1_000L));
            OrderModel draft = new OrderModel(1L, items);
            OrderModel pointConfirmed = new OrderModel(1L, items);
            pointConfirmed.confirm(2_000L);
            OrderModel failed = new OrderModel(1L, items);
            failed.startPgPayment();
            failed.failPgPayment();

            // act
            OrderModel confirmed = pgConfirmed(items);

            // assert
            assertThat(confirmed.getConfirmedAt()).isNotNull();
            assertThat(confirmed.getConfirmedAt().getNano() % 1_000_000).as("밀리초보다 작은 값은 버린다").isZero();
            assertThat(draft.getConfirmedAt()).isNull();
            assertThat(pointConfirmed.getConfirmedAt()).as("포인트 확정은 확정 시각을 만들지 않는다").isNull();
            assertThat(failed.getConfirmedAt()).isNull();
        }
    }

    @DisplayName("주입한 시각으로 확정하면, 그 시각(밀리초 정밀도)이 확정 시각이 되고 이벤트에 그대로 담긴다.")
    @Test
    void usesInjectedConfirmationTime() {
        // arrange
        OrderModel order = new OrderModel(1L, List.of(new OrderItem(7L, 2, 1_000L)));
        order.startPgPayment();

        // act
        order.completePgPayment(2_000L, Instant.parse("2026-09-07T01:00:00.123456789Z"));

        // assert
        assertThat(order.getConfirmedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00.123Z"));
        assertThat(OrderConfirmedEvent.from(order).confirmedAt()).isEqualTo("2026-09-07T01:00:00.123Z");
    }

    @DisplayName("이벤트는, ")
    @Nested
    class Event {

        @DisplayName("저장된 확정 사실에서 만들어지고, eventId와 key는 주문에서만 정해진다.")
        @Test
        void isBuiltFromStoredFacts() {
            // arrange
            OrderModel order = pgConfirmed(List.of(new OrderItem(7L, 2, 1_000L)));

            // act
            OrderConfirmedEvent first = OrderConfirmedEvent.from(order);
            OrderConfirmedEvent second = OrderConfirmedEvent.from(order);

            // assert
            assertThat(first.eventType()).isEqualTo("OrderConfirmed");
            assertThat(first.schemaVersion()).isEqualTo(1);
            assertThat(first.eventId()).isEqualTo("order-confirmed:v1:" + order.getId());
            assertThat(first.key()).isEqualTo(String.valueOf(order.getId()));
            assertThat(first.confirmedAt()).isEqualTo(order.getConfirmedAt().toString());
            assertThat(first).as("같은 사실에서 다시 만들어도 같다").isEqualTo(second);
        }

        @DisplayName("같은 상품은 수량을 합치고 상품 id 순으로 둔다.")
        @Test
        void mergesDuplicateProductsAndSortsByProductId() {
            // arrange
            OrderModel order = pgConfirmed(List.of(
                new OrderItem(8L, 1, 1_000L),
                new OrderItem(7L, 2, 1_000L),
                new OrderItem(8L, 3, 1_000L)
            ));

            // act
            OrderConfirmedEvent event = OrderConfirmedEvent.from(order);

            // assert
            assertThat(event.items()).containsExactly(
                new OrderConfirmedEvent.Item(7L, 2),
                new OrderConfirmedEvent.Item(8L, 4)
            );
        }

        @DisplayName("확정 시각이 없는 주문(포인트 확정, 확정 전, 실패)에서는 만들지 않는다.")
        @Test
        void isNotBuilt_whenOrderHasNoConfirmedAt() {
            // arrange
            List<OrderItem> items = List.of(new OrderItem(7L, 2, 1_000L));
            OrderModel pointConfirmed = new OrderModel(1L, items);
            pointConfirmed.confirm(2_000L);
            OrderModel pending = new OrderModel(1L, items);
            pending.startPgPayment();

            // act & assert
            assertThatThrownBy(() -> OrderConfirmedEvent.from(pointConfirmed)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> OrderConfirmedEvent.from(pending)).isInstanceOf(IllegalStateException.class);
        }
    }
}
