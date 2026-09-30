package com.loopers.domain.ordering.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderTest {

    @DisplayName("주문 생성")
    @Nested
    class Create {
        @DisplayName("품목 금액의 합으로 총액을 계산하고 DRAFT로 생성한다")
        @Test
        void createsDraftOrder_withSummedTotalAmount() {
            List<OrderItem> items = List.of(
                OrderItem.create(1L, "상품1", 1_000L, 2),
                OrderItem.create(2L, "상품2", 3_000L, 1)
            );

            Order order = Order.create(1L, items);

            assertThat(order.getUserId()).isEqualTo(1L);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getTotalAmount()).isEqualTo(5_000L);
            assertThat(order.getItems()).hasSize(2);
        }

        @DisplayName("품목이 없으면 거절한다")
        @Test
        void rejectsCreate_whenItemsIsEmpty() {
            assertThatThrownBy(() -> Order.create(1L, List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.EMPTY_ORDER_ITEMS);
        }

        @DisplayName("총액 계산이 범위를 초과하면 거절한다")
        @Test
        void rejectsCreate_whenTotalAmountOverflows() {
            List<OrderItem> items = List.of(
                OrderItem.create(1L, "상품1", Long.MAX_VALUE, 1),
                OrderItem.create(2L, "상품2", 1L, 1)
            );

            assertThatThrownBy(() -> Order.create(1L, items))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.CALCULATION_OVERFLOW);
        }

        @DisplayName("0 이하 사용자 ID는 거절한다")
        @Test
        void rejectsCreate_whenUserIdIsNotPositive() {
            List<OrderItem> items = List.of(OrderItem.create(1L, "상품", 1_000L, 1));

            assertThatThrownBy(() -> Order.create(0L, items)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("주문 복원")
    @Nested
    class Restore {
        @DisplayName("저장된 합계가 품목 금액의 합과 일치하면 복원한다")
        @Test
        void restoresOrder_whenTotalAmountMatches() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 2, 2_000L));

            Order order = Order.restore(1L, 1L, OrderStatus.DRAFT, items, 2_000L, Instant.now(), null);

            assertThat(order.getTotalAmount()).isEqualTo(2_000L);
        }

        @DisplayName("저장된 합계가 품목 금액의 합과 다르면 거절한다")
        @Test
        void rejectsRestore_whenTotalAmountDoesNotMatch() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 2, 2_000L));

            assertThatThrownBy(() -> Order.restore(1L, 1L, OrderStatus.DRAFT, items, 1_999L, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("id가 없거나 생성 시각이 없으면 거절한다")
        @Test
        void rejectsRestore_whenMetadataIsMissing() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 1, 1_000L));

            assertThatThrownBy(() -> Order.restore(0L, 1L, OrderStatus.DRAFT, items, 1_000L, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Order.restore(1L, 1L, OrderStatus.DRAFT, items, 1_000L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("CONFIRMED인데 주문 기록이 없으면 거절한다")
        @Test
        void rejectsRestore_whenConfirmedWithoutRecord() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 1, 1_000L));

            assertThatThrownBy(() -> Order.restore(1L, 1L, OrderStatus.CONFIRMED, items, 1_000L, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("DRAFT인데 주문 기록이 있으면 거절한다")
        @Test
        void rejectsRestore_whenDraftWithRecord() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 1, 1_000L));
            OrderRecord record = OrderRecord.restore(1L, 1L, 1_000L, OrderRecordStatus.PAID, Instant.now());

            assertThatThrownBy(() -> Order.restore(1L, 1L, OrderStatus.DRAFT, items, 1_000L, Instant.now(), record))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("주문 확정")
    @Nested
    class Confirm {
        @DisplayName("DRAFT 주문을 CONFIRMED로 전환한다")
        @Test
        void confirmsDraftOrder() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 1, 1_000L));
            Order order = Order.restore(1L, 1L, OrderStatus.DRAFT, items, 1_000L, Instant.now(), null);

            order.confirm();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("확정하면 사용자·총액을 담은 PAID 상태의 주문 기록을 보유하고 DRAFT는 기록이 없다")
        @Test
        void confirmHoldsPaidOrderRecord_withUserIdAndTotalAmount() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 2, 2_000L));
            Order order = Order.restore(1L, 1L, OrderStatus.DRAFT, items, 2_000L, Instant.now(), null);

            assertThat(order.getRecord()).isEmpty();

            order.confirm();

            OrderRecord orderRecord = order.getRecord().orElseThrow();
            assertThat(orderRecord.getUserId()).isEqualTo(order.getUserId());
            assertThat(orderRecord.getAmount()).isEqualTo(order.getTotalAmount());
            assertThat(orderRecord.getStatus()).isEqualTo(OrderRecordStatus.PAID);
        }

        @DisplayName("이미 CONFIRMED인 주문은 재확정을 거절하고 상태를 유지한다")
        @Test
        void rejectsReconfirm_andKeepsConfirmedStatus() {
            List<OrderItem> items = List.of(OrderItem.restore(1L, "상품", 1_000L, 1, 1_000L));
            OrderRecord record = OrderRecord.restore(1L, 1L, 1_000L, OrderRecordStatus.PAID, Instant.now());
            Order order = Order.restore(1L, 1L, OrderStatus.CONFIRMED, items, 1_000L, Instant.now(), record);

            assertThatThrownBy(order::confirm)
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }
    }
}
