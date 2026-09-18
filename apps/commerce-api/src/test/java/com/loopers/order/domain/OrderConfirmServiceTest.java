package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfirmServiceTest {

    private static final ZonedDateTime PAID_AT =
        ZonedDateTime.parse("2026-09-17T12:00:00+09:00");

    @DisplayName("[R-ORDER-11] 확정에 성공하면 상품 재고와 고객 포인트를 함께 차감한다.")
    @Nested
    class ConfirmSuccessfully {

        @DisplayName("[상태 전이] 재고 5와 잔액 10000에서 2개·4000원 주문을 확정한다.")
        @Test
        void decreasesStockAndPoint_andConfirmsOrder() {
            Scenario scenario = scenario(5, 10_000L);

            scenario.service().confirm(
                1L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT
            );

            assertAll(
                () -> assertStockQuantity(scenario.product(), 3),
                () -> assertPointBalance(scenario.buyer(), 6_000L),
                () -> assertThat(scenario.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED)
            );
        }
    }

    @DisplayName("[R-ORDER-12] 확정에 성공하면 결제 결과와 CONFIRMED 상태를 남긴다.")
    @Nested
    class SavePaymentResult {

        @DisplayName("[상태 전이] 주문 합계와 결제 시점을 결제 결과로 남긴다.")
        @Test
        void savesPaymentResult() {
            Scenario scenario = scenario(5, 10_000L);

            scenario.service().confirm(
                1L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT
            );

            assertAll(
                () -> assertThat(scenario.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertPaymentResult(scenario.order(), 4_000L, PAID_AT)
            );
        }
    }

    @DisplayName("[R-ACCESS-03] 고객은 자신의 주문만 확정할 수 있다.")
    @Nested
    class RejectOtherUsersOrder {

        @DisplayName("[의사결정표] 다른 고객의 주문이면 없는 주문으로 거절하고 모두 유지한다.")
        @Test
        void throwsOrderNotFound_andKeepsAllState() {
            Scenario scenario = scenario(5, 10_000L);

            CoreException result = assertThrows(CoreException.class, () -> scenario.service().confirm(
                2L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT
            ));

            assertRejectedState(scenario, result, ErrorCode.ORDER_NOT_FOUND, 5, 10_000L);
        }
    }

    @DisplayName("[P-ORDER-04] 이미 확정된 주문은 다시 확정할 수 없다.")
    @Nested
    class RejectConfirmedOrder {

        @DisplayName("[상태 전이] 이미 확정된 주문이면 거절하고 재고와 포인트를 유지한다.")
        @Test
        void throwsOrderAlreadyConfirmed_andKeepsStockAndPoint() {
            Scenario scenario = scenario(5, 10_000L);
            scenario.order().confirm(4_000L, PAID_AT);

            CoreException result = assertThrows(CoreException.class, () -> scenario.service().confirm(
                1L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT.plusMinutes(1)
            ));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertStockQuantity(scenario.product(), 5),
                () -> assertPointBalance(scenario.buyer(), 10_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-07] 삭제된 상품이 포함된 주문은 확정할 수 없다.")
    @Nested
    class RejectDeletedProduct {

        @DisplayName("[의사결정표] 상품이 삭제됐으면 판매 불가로 거절하고 모두 유지한다.")
        @Test
        void throwsProductNotAvailable_andKeepsAllState() {
            Scenario scenario = scenario(5, 10_000L);
            scenario.product().delete();

            CoreException result = assertThrows(CoreException.class, () -> scenario.service().confirm(
                1L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT
            ));

            assertRejectedState(scenario, result, ErrorCode.PRODUCT_NOT_AVAILABLE, 5, 10_000L);
        }
    }

    @DisplayName("[R-ORDER-08] 각 상품의 재고가 주문 수량 이상이어야 한다.")
    @Nested
    class RequireEnoughStock {

        @DisplayName("[경계값 분석] 재고가 주문 수량보다 1 적으면 재고 부족으로 거절한다.")
        @Test
        void throwsInsufficientStock_whenStockIsOneLessThanQuantity() {
            Scenario scenario = scenario(1, 10_000L);

            CoreException result = assertThrows(CoreException.class, () -> scenario.service().confirm(
                1L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT
            ));

            assertRejectedState(scenario, result, ErrorCode.INSUFFICIENT_STOCK, 1, 10_000L);
        }
    }

    @DisplayName("[R-ORDER-09] 고객의 포인트 잔액이 주문 금액 이상이어야 한다.")
    @Nested
    class RequireEnoughPoint {

        @DisplayName("[경계값 분석] 잔액이 주문 금액보다 1 적으면 포인트 부족으로 거절한다.")
        @Test
        void throwsInsufficientPoint_whenPointIsOneLessThanTotal() {
            Scenario scenario = scenario(5, 3_999L);

            CoreException result = assertThrows(CoreException.class, () -> scenario.service().confirm(
                1L,
                scenario.order(),
                List.of(scenario.product()),
                scenario.buyer(),
                PAID_AT
            ));

            assertRejectedState(scenario, result, ErrorCode.INSUFFICIENT_POINT, 5, 3_999L);
        }
    }

    @DisplayName("[R-ORDER-10] 재고나 포인트가 부족하면 아무 상태도 바꾸지 않는다.")
    @Nested
    class KeepAllStateOnRejection {

        @DisplayName("[의사결정표] 재고 부족과 포인트 부족 각각에서 주문·재고·포인트를 유지한다.")
        @Test
        void keepsAllState_whenStockOrPointIsInsufficient() {
            Scenario insufficientStock = scenario(1, 10_000L);
            Scenario insufficientPoint = scenario(5, 3_999L);

            assertThrows(CoreException.class, () -> insufficientStock.service().confirm(
                1L,
                insufficientStock.order(),
                List.of(insufficientStock.product()),
                insufficientStock.buyer(),
                PAID_AT
            ));
            assertThrows(CoreException.class, () -> insufficientPoint.service().confirm(
                1L,
                insufficientPoint.order(),
                List.of(insufficientPoint.product()),
                insufficientPoint.buyer(),
                PAID_AT
            ));

            assertAll(
                () -> assertThat(insufficientStock.order().getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertStockQuantity(insufficientStock.product(), 1),
                () -> assertPointBalance(insufficientStock.buyer(), 10_000L),
                () -> assertThat(insufficientPoint.order().getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertStockQuantity(insufficientPoint.product(), 5),
                () -> assertPointBalance(insufficientPoint.buyer(), 3_999L)
            );
        }
    }

    private static Scenario scenario(int stock, long point) {
        Product product = new Product(1L, "상품", 2_000L);
        product.changeStock(stock);
        User buyer = new User();
        buyer.charge(point);
        Order order = new Order(1L, List.of(OrderItem.of(product, 2)));
        return new Scenario(new OrderConfirmService(), product, buyer, order);
    }

    private static void assertRejectedState(
        Scenario scenario,
        CoreException exception,
        ErrorCode expectedError,
        int expectedStock,
        long expectedPoint
    ) {
        assertAll(
            () -> assertThat(exception.getErrorCode()).isEqualTo(expectedError),
            () -> assertThat(scenario.order().getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(scenario.order().getPaymentResult()).isNull(),
            () -> assertStockQuantity(scenario.product(), expectedStock),
            () -> assertPointBalance(scenario.buyer(), expectedPoint)
        );
    }

    private static void assertStockQuantity(Product product, int expectedQuantity) {
        assertThat(product.getStock())
            .isNotNull()
            .extracting(stock -> stock.quantity())
            .isEqualTo(expectedQuantity);
    }

    private static void assertPointBalance(User user, long expectedBalance) {
        assertThat(user.getPoint())
            .isNotNull()
            .extracting(point -> point.balance())
            .isEqualTo(expectedBalance);
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

    private record Scenario(
        OrderConfirmService service,
        Product product,
        User buyer,
        Order order
    ) {
    }
}
