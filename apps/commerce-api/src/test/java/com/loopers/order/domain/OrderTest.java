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

    @DisplayName("[INV-27] 주문에는 품목이 하나 이상 있다.")
    @Nested
    class AtLeastOneItem {

        @DisplayName("[경계값 분석] 품목 1개로 주문을 만들 수 있다.")
        @Test
        void createsOrder_whenItemCountIsOne() {
            // act
            Order order = new Order(1L, List.of(new OrderItem(10L, "상품 A", 2, 2_000L)));

            // assert
            assertThat(order.getItems()).hasSize(1);
        }

        @DisplayName("[동등 클래스 분할] 구매자와 여러 품목으로 주문을 만든다.")
        @Test
        void createsOrderWithMultipleItems() {
            // act
            Order order = orderOfTwoProducts();

            // assert
            assertAll(
                () -> assertThat(order.getBuyerId()).isEqualTo(1L),
                () -> assertThat(order.getItems()).hasSize(2)
            );
        }

        @DisplayName("[경계값 분석] 품목이 0개이면 빈 주문 오류로 거절한다.")
        @Test
        void throwsEmptyOrderItems_whenItemsAreEmpty() {
            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> new Order(1L, List.of())
            );

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.EMPTY_ORDER_ITEMS);
        }
    }

    @DisplayName("[INV-28] 한 주문에 상품마다 품목이 하나다. 같은 상품이 여러 번 들어오면 수량이 합산된다.")
    @Nested
    class OneItemPerProduct {

        @DisplayName("[동등 클래스 분할] 같은 상품의 수량 2와 3을 품목 하나의 수량 5로 합친다.")
        @Test
        void mergesQuantity_whenProductIsDuplicated() {
            // act
            Order order = new Order(1L, List.of(
                new OrderItem(10L, "상품", 2, 2_000L),
                new OrderItem(10L, "상품", 3, 2_000L)
            ));

            // assert
            assertAll(
                () -> assertThat(order.getItems()).hasSize(1),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(5),
                () -> assertThat(order.getTotalAmount()).isEqualTo(10_000L)
            );
        }

        @DisplayName("[동등 클래스 분할] 다른 상품은 각각 유지하고 같은 상품만 합산한다.")
        @Test
        void keepsOneItemPerProduct() {
            // act
            Order order = new Order(1L, List.of(
                new OrderItem(10L, "상품 A", 1, 1_000L),
                new OrderItem(20L, "상품 B", 1, 2_000L),
                new OrderItem(10L, "상품 A", 2, 1_000L)
            ));

            // assert
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

    @DisplayName("[INV-29] 주문 합계는 품목 금액의 합이다.")
    @Nested
    class TotalIsSumOfItemAmounts {

        @DisplayName("[동등 클래스 분할] 4000원과 3000원 품목의 합계는 7000원이다.")
        @Test
        void sumsItemAmounts() {
            // arrange
            Order order = orderOfTwoProducts();

            // assert
            assertThat(order.getTotalAmount()).isEqualTo(7_000L);
        }

        @DisplayName("[상태 전이] 품목 수량을 바꾸면 합계를 다시 계산한다.")
        @Test
        void recalculatesTotal_whenQuantityChanges() {
            // arrange
            Order order = orderOfTwoProducts();

            // act
            order.changeItemQuantity(1L, 10L, 3);

            // assert
            assertThat(order.getTotalAmount()).isEqualTo(9_000L);
        }
    }

    @DisplayName("[INV-30] 새 주문의 상태는 DRAFT다.")
    @Nested
    class InitialStatus {

        @DisplayName("[상태 전이] 새 주문의 상태는 DRAFT다.")
        @Test
        void startsAsDraft() {
            // act
            Order order = orderOfTwoProducts();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        }
    }

    @DisplayName("[INV-31] 주문은 구매자의 것이다.")
    @Nested
    class OwnedByBuyer {

        @DisplayName("[동등 클래스 분할] 구매자는 자신의 주문으로 확인되고 다른 고객은 아니다.")
        @Test
        void identifiesOwner() {
            // arrange
            Order order = orderOfTwoProducts();

            // assert
            assertAll(
                () -> assertThat(order.isOwnedBy(1L)).isTrue(),
                () -> assertThat(order.isOwnedBy(2L)).isFalse()
            );
        }

        @DisplayName("[동등 클래스 분할] 다른 고객이 품목 수량을 바꾸면 없는 주문으로 거절하고, 수량은 그대로다.")
        @Test
        void throwsOrderNotFound_whenRequesterIsNotOwner() {
            // arrange
            Order order = orderOfTwoProducts();

            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> order.changeItemQuantity(2L, 10L, 3)
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[INV-34] 확정된 주문은 결제 결과를 갖는다. DRAFT 주문은 갖지 않는다.")
    @Nested
    class PaymentResultFollowsStatus {

        @DisplayName("[상태 전이] 새 주문에는 결제 결과가 없다.")
        @Test
        void hasNoPaymentResult_whenDraft() {
            // act
            Order order = orderOfTwoProducts();

            // assert
            assertThat(order.getPaymentResult()).isNull();
        }

        @DisplayName("[상태 전이] 확정하면 결제액과 결제 시점을 가진 결제 결과가 생긴다.")
        @Test
        void hasPaymentResult_whenConfirmed() {
            // arrange
            Order order = orderOfTwoProducts();
            ZonedDateTime paidAt = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");

            // act
            order.confirm(7_000L, paidAt);

            // assert
            assertPaymentResult(order, 7_000L, paidAt);
        }
    }

    @DisplayName("[INV-37] 확정된 주문의 결제 결과는 바뀌지 않는다.")
    @Nested
    class ImmutablePaymentResult {

        @DisplayName("[상태 전이] 재확정을 이미 확정된 주문으로 거절하고, 상태와 결제 결과는 그대로다.")
        @Test
        void throwsOrderAlreadyConfirmed_andKeepsPaymentResult() {
            // arrange
            Order order = orderOfTwoProducts();
            ZonedDateTime firstPaidAt = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");
            order.confirm(7_000L, firstPaidAt);

            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> order.confirm(8_000L, firstPaidAt.plusMinutes(1))
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertPaymentResult(order, 7_000L, firstPaidAt)
            );
        }
    }

    @DisplayName("[INV-38] 품목 수량이 바뀌는 주문의 상태는 DRAFT다.")
    @Nested
    class ChangeQuantityOnDraftOnly {

        @DisplayName("[상태 전이] DRAFT 주문의 품목 수량은 바꿀 수 있다.")
        @Test
        void changesQuantity_whenDraft() {
            // arrange
            Order order = orderOfTwoProducts();

            // act
            order.changeItemQuantity(1L, 10L, 3);

            // assert
            assertThat(order.getItems().get(0).quantity()).isEqualTo(3);
        }

        @DisplayName("[상태 전이] 확정된 주문의 품목 수량 변경을 거절하고, 수량은 그대로다.")
        @Test
        void throwsOrderAlreadyConfirmed_andKeepsItem() {
            // arrange
            Order order = orderOfTwoProducts();
            order.confirm(7_000L, ZonedDateTime.now());

            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> order.changeItemQuantity(1L, 10L, 3)
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[INV-39] 품목 수량은 양수다.")
    @Nested
    class PositiveChangedQuantity {

        @DisplayName("[경계값 분석] 품목 수량을 0으로 바꾸면 주문 수량 오류로 거절하고, 수량은 그대로다.")
        @Test
        void throwsInvalidOrderQuantity_andKeepsItem() {
            // arrange
            Order order = orderOfTwoProducts();

            // act
            CoreException result = assertThrows(
                CoreException.class,
                () -> order.changeItemQuantity(1L, 10L, 0)
            );

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_QUANTITY),
                () -> assertThat(order.getItems().get(0).quantity()).isEqualTo(2)
            );
        }
    }

    @DisplayName("[INV-40] 품목은 주문을 생성한 시점의 상품 이름과 단가를 갖는다.")
    @Nested
    class KeepSnapshotOnQuantityChange {

        @DisplayName("[상태 전이] 수량을 바꿔도 품목의 상품 이름과 단가는 그대로다.")
        @Test
        void keepsUnitPrice_whenQuantityChanges() {
            // arrange
            Order order = orderOfTwoProducts();

            // act
            order.changeItemQuantity(1L, 10L, 3);

            // assert
            assertAll(
                () -> assertThat(order.getItems().get(0).productName()).isEqualTo("상품 A"),
                () -> assertThat(order.getItems().get(0).unitPrice()).isEqualTo(2_000L)
            );
        }
    }

    @DisplayName("[INV-44] 확정에 성공한 주문의 상태는 CONFIRMED다.")
    @Nested
    class ConfirmedStatus {

        @DisplayName("[상태 전이] DRAFT 주문을 확정하면 상태가 CONFIRMED가 된다.")
        @Test
        void becomesConfirmed() {
            // arrange
            Order order = orderOfTwoProducts();

            // act
            order.confirm(7_000L, ZonedDateTime.parse("2026-09-17T12:00:00+09:00"));

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
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
