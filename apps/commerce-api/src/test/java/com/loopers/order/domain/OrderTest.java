package com.loopers.order.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    @DisplayName("[R-ORDER-01] 고객은 여러 상품과 각 상품의 수량으로 주문을 생성할 수 있다.")
    @Nested
    class CreateOrder {

        @DisplayName("[동등 클래스 분할] 구매자와 여러 품목으로 주문을 만든다.")
        @Test
        void createsOrderWithMultipleItems() {
            Order order = new Order(1L, List.of(
                new OrderItem(10L, "상품 A", 2, 2_000L),
                new OrderItem(20L, "상품 B", 1, 3_000L)
            ));

            assertAll(
                () -> assertThat(order.getBuyerId()).isEqualTo(1L),
                () -> assertThat(order.getItems()).hasSize(2)
            );
        }
    }

    @DisplayName("[P-ORDER-01] 주문에는 품목이 하나 이상 있어야 한다.")
    @Nested
    class RejectEmptyItems {

        @DisplayName("[경계값 분석] 품목이 0개이면 빈 주문 오류로 거절한다.")
        @Test
        void throwsEmptyOrderItems_whenItemsAreEmpty() {
            CoreException result = assertThrows(
                CoreException.class,
                () -> new Order(1L, List.of())
            );

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.EMPTY_ORDER_ITEMS);
        }
    }

    @DisplayName("[R-ORDER-02] 주문 합계는 품목 금액의 합이다.")
    @Nested
    class CalculateTotalAmount {

        @DisplayName("[동등 클래스 분할] 4000원과 3000원 품목의 합계는 7000원이다.")
        @Test
        void sumsItemAmounts() {
            Order order = orderOfTwoProducts();

            assertThat(order.getTotalAmount()).isEqualTo(7_000L);
        }
    }

    @DisplayName("[R-ORDER-03] 주문을 생성하면 결제 전 상태로 저장된다.")
    @Nested
    class InitialStatus {

        @DisplayName("[상태 전이] 새 주문은 DRAFT이고 결제 결과가 없다.")
        @Test
        void startsAsDraftWithoutPaymentResult() {
            Order order = orderOfTwoProducts();

            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getPaymentResult()).isNull()
            );
        }
    }

    @DisplayName("[R-ORDER-15] 같은 상품이 여러 번 들어오면 수량을 합산한다.")
    @Nested
    class MergeDuplicatedItems {

        @DisplayName("[의사결정표] 같은 상품의 수량 2와 3을 품목 하나의 수량 5로 합친다.")
        @Test
        void mergesQuantity_whenProductIsDuplicated() {
            Order order = new Order(1L, List.of(
                new OrderItem(10L, "상품", 2, 2_000L),
                new OrderItem(10L, "상품", 3, 2_000L)
            ));

            assertAll(
                () -> assertThat(order.getItems()).hasSize(1),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(5),
                () -> assertThat(order.getTotalAmount()).isEqualTo(10_000L)
            );
        }
    }

    @DisplayName("[P-ORDER-02] 한 주문에는 상품마다 품목이 하나만 존재한다.")
    @Nested
    class OneItemPerProduct {

        @DisplayName("[의사결정표] 다른 상품은 각각 유지하고 같은 상품만 합산한다.")
        @Test
        void keepsOneItemPerProduct() {
            Order order = new Order(1L, List.of(
                new OrderItem(10L, "상품 A", 1, 1_000L),
                new OrderItem(20L, "상품 B", 1, 2_000L),
                new OrderItem(10L, "상품 A", 2, 1_000L)
            ));

            assertAll(
                () -> assertThat(order.getItems()).hasSize(2),
                () -> assertThat(order.getItems())
                    .filteredOn(item -> item.productId().equals(10L))
                    .singleElement()
                    .extracting(OrderItem::quantity)
                    .isEqualTo(3)
            );
        }
    }

    @DisplayName("[R-ACCESS-03] 고객은 자신의 주문만 다룰 수 있다.")
    @Nested
    class OwnedOrder {

        @DisplayName("[의사결정표] 구매자는 자신의 주문으로 확인된다.")
        @Test
        void identifiesOwner() {
            Order order = orderOfTwoProducts();

            assertAll(
                () -> assertThat(order.isOwnedBy(1L)).isTrue(),
                () -> assertThat(order.isOwnedBy(2L)).isFalse()
            );
        }

        @DisplayName("[의사결정표] 다른 고객이 품목 수량을 바꾸면 없는 주문으로 거절한다.")
        @Test
        void throwsOrderNotFound_whenRequesterIsNotOwner() {
            Order order = orderOfTwoProducts();

            CoreException result = assertThrows(
                CoreException.class,
                () -> order.changeItemQuantity(2L, 10L, 3)
            );

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[R-ORDER-06] 변경한 주문 품목 수량도 양수여야 한다.")
    @Nested
    class PositiveChangedQuantity {

        @DisplayName("[경계값 분석] 품목 수량을 0으로 바꾸면 거절하고 기존 수량을 유지한다.")
        @Test
        void throwsInvalidOrderQuantity_andKeepsItem() {
            Order order = orderOfTwoProducts();

            CoreException result = assertThrows(
                CoreException.class,
                () -> order.changeItemQuantity(1L, 10L, 0)
            );

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_QUANTITY),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[P-ORDER-03] 품목 수량을 바꿔도 주문 당시 단가를 유지한다.")
    @Nested
    class ChangeQuantityWithSnapshotPrice {

        @DisplayName("[상태 전이] 수량 변경 후 기존 단가로 합계를 다시 계산한다.")
        @Test
        void recalculatesTotalWithOriginalUnitPrice() {
            Order order = orderOfTwoProducts();

            order.changeItemQuantity(1L, 10L, 3);

            assertAll(
                () -> assertThat(order.getItems().get(0).unitPrice()).isEqualTo(2_000L),
                () -> assertThat(order.getTotalAmount()).isEqualTo(9_000L)
            );
        }
    }

    @DisplayName("[P-ORDER-05] DRAFT 주문에서만 품목 수량을 바꿀 수 있다.")
    @Nested
    class ChangeDraftOrderOnly {

        @DisplayName("[상태 전이] 확정된 주문의 품목 수량 변경을 거절하고 상태를 유지한다.")
        @Test
        void throwsOrderAlreadyConfirmed_andKeepsItem() {
            Order order = orderOfTwoProducts();
            order.confirm(7_000L, ZonedDateTime.now());

            CoreException result = assertThrows(
                CoreException.class,
                () -> order.changeItemQuantity(1L, 10L, 3)
            );

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[R-ORDER-12] 주문 확정 시 결제 결과를 남기고 CONFIRMED로 변경한다.")
    @Nested
    class ConfirmOrder {

        @DisplayName("[상태 전이] DRAFT 주문을 확정하면 상태와 결제 결과가 함께 바뀐다.")
        @Test
        void confirmsDraftOrderWithPaymentResult() {
            Order order = orderOfTwoProducts();
            ZonedDateTime paidAt = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");

            order.confirm(7_000L, paidAt);

            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertPaymentResult(order, 7_000L, paidAt)
            );
        }
    }

    @DisplayName("[P-ORDER-04] 이미 확정된 주문을 다시 확정하면 거절한다.")
    @Nested
    class RejectRepeatedConfirmation {

        @DisplayName("[상태 전이] 재확정을 거절하고 기존 결제 결과를 유지한다.")
        @Test
        void throwsOrderAlreadyConfirmed_andKeepsPaymentResult() {
            Order order = orderOfTwoProducts();
            ZonedDateTime firstPaidAt = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");
            order.confirm(7_000L, firstPaidAt);

            CoreException result = assertThrows(
                CoreException.class,
                () -> order.confirm(8_000L, firstPaidAt.plusMinutes(1))
            );

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertPaymentResult(order, 7_000L, firstPaidAt)
            );
        }
    }

    @DisplayName("[P-ORDER-06] 결제 결과에는 결제액과 결제 시점이 포함된다.")
    @Nested
    class PaymentResultFields {

        @DisplayName("[동등 클래스 분할] 확정된 주문에서 결제액과 결제 시점을 조회한다.")
        @Test
        void exposesAmountAndPaidAt_afterConfirmation() {
            Order order = orderOfTwoProducts();
            ZonedDateTime paidAt = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");

            order.confirm(7_000L, paidAt);

            assertAll(
                () -> assertPaymentResult(order, 7_000L, paidAt)
            );
        }
    }

    private static void assertPaymentResult(
        Order order,
        long expectedAmount,
        ZonedDateTime expectedPaidAt
    ) {
        assertThat(order.getPaymentResult())
            .isNotNull()
            .satisfies(paymentResult -> assertAll(
                () -> assertThat(paymentResult.amount()).isEqualTo(expectedAmount),
                () -> assertThat(paymentResult.paidAt()).isEqualTo(expectedPaidAt)
            ));
    }

    private static Order orderOfTwoProducts() {
        return new Order(1L, List.of(
            new OrderItem(10L, "상품 A", 2, 2_000L),
            new OrderItem(20L, "상품 B", 1, 3_000L)
        ));
    }
}
