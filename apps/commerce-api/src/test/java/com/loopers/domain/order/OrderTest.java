package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    private static final Long USER_ID = 1L;

    private static OrderLine line(long productId, long unitPrice, int quantity) {
        return new OrderLine(productId, "상품" + productId, unitPrice, quantity);
    }

    @DisplayName("DRAFT 주문을 만들 때, ")
    @Nested
    class Draft {
        @DisplayName("품목과 합계를 담은 DRAFT 가 되고, 결제 정보는 비어 있다. (ORD-05)")
        @Test
        void createsDraftWithTotal() {
            // act
            Order order = Order.draft(USER_ID, List.of(line(1L, 1_000L, 2), line(2L, 2_500L, 2)));

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getTotalAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(order.getPaidAt()).isNull()
            );
        }

        @DisplayName("같은 상품의 품목은 처음 등장한 위치에 하나로 합산한다. (ORD-03, D-33)")
        @Test
        void mergesSameProductAtFirstPosition() {
            // act
            Order order = Order.draft(USER_ID, List.of(line(1L, 1_000L, 2), line(2L, 500L, 1), line(1L, 1_000L, 3)));

            // assert
            assertAll(
                () -> assertThat(order.getItems()).extracting(OrderItem::getProductId, OrderItem::getQuantity)
                    .containsExactly(tuple(1L, 5), tuple(2L, 1)),
                () -> assertThat(order.getTotalAmount()).isEqualTo(5_500L)
            );
        }

        @DisplayName("품목이 없으면, EMPTY_ORDER_ITEMS 예외가 발생한다. (ORD-04)")
        @Test
        void throwsEmptyOrderItems_whenNoLines() {
            CoreException result = assertThrows(CoreException.class, () -> Order.draft(USER_ID, List.of()));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }

        @DisplayName("수량이 1 미만이거나 999 를 넘으면, INVALID_QUANTITY 예외가 발생한다. (ORD-02)")
        @ParameterizedTest
        @ValueSource(ints = {0, -1, 1_000})
        void throwsInvalidQuantity_whenQuantityIsOutOfRange(int quantity) {
            CoreException result = assertThrows(CoreException.class, () -> Order.draft(USER_ID, List.of(line(1L, 1_000L, quantity))));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_QUANTITY);
        }

        @DisplayName("줄마다는 범위 안이어도 합산한 수량이 999 를 넘으면, INVALID_QUANTITY 예외가 발생한다.")
        @Test
        void throwsInvalidQuantity_whenMergedQuantityExceedsLimit() {
            CoreException result = assertThrows(CoreException.class,
                () -> Order.draft(USER_ID, List.of(line(1L, 1_000L, 600), line(1L, 1_000L, 600))));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_QUANTITY);
        }

        @DisplayName("합계 0 원 주문은 허용한다. (0원 상품, 설계 5.2)")
        @Test
        void allowsZeroTotal() {
            assertThat(Order.draft(USER_ID, List.of(line(1L, 0L, 3))).getTotalAmount()).isZero();
        }
    }

    @DisplayName("확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("DRAFT 면, CONFIRMED 가 되고 결제액과 결제 시각을 남긴다.")
        @Test
        void confirmsDraft() {
            // arrange
            Order order = Order.draft(USER_ID, List.of(line(1L, 7_000L, 1)));
            ZonedDateTime paidAt = ZonedDateTime.now();

            // act
            order.confirm(7_000L, paidAt);

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAt()).isEqualTo(paidAt)
            );
        }

        @DisplayName("이미 확정된 주문이면, ORDER_ALREADY_CONFIRMED 예외가 발생하고 결제 정보는 그대로다. (ORD-07)")
        @Test
        void throwsAlreadyConfirmed_andKeepsPayment() {
            // arrange
            Order order = Order.draft(USER_ID, List.of(line(1L, 7_000L, 1)));
            ZonedDateTime paidAt = ZonedDateTime.now();
            order.confirm(7_000L, paidAt);

            // act
            CoreException result = assertThrows(CoreException.class, () -> order.confirm(1L, paidAt.plusDays(1)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(7_000L),
                () -> assertThat(order.getPaidAt()).isEqualTo(paidAt)
            );
        }
    }

    @DisplayName("품목의 가격 일치 판단은 스냅샷 단가와 같을 때만 참이고, 오르거나 내려도 거짓이다. (ORD-09)")
    @Test
    void matchesOnlySamePrice() {
        OrderItem item = Order.draft(USER_ID, List.of(line(1L, 1_000L, 1))).getItems().get(0);

        assertAll(
            () -> assertThat(item.isPriceMatched(1_000L)).isTrue(),
            () -> assertThat(item.isPriceMatched(1_200L)).isFalse(),
            () -> assertThat(item.isPriceMatched(800L)).isFalse()
        );
    }
}
