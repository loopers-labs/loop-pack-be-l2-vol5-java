package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfirmServiceTest {

    private static final ZonedDateTime PAID_AT =
        ZonedDateTime.parse("2026-09-17T12:00:00+09:00");
    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    /** 상품 A 2개(2,000원) + 상품 B 1개(3,000원) */
    private static final long TOTAL_AMOUNT = 7_000L;

    @DisplayName("[INV-36] 확정에 성공하면 재고는 품목 수량만큼, 잔액은 주문 합계만큼 줄어든다.")
    @Nested
    class DecreaseStockAndBalance {

        @DisplayName("[상태 전이] 재고 5·4와 잔액 10000에서 확정하면 재고 3·3, 잔액 3000이 된다.")
        @Test
        void decreasesEveryProductStock_andBalance() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);

            // act
            scenario.confirm(BUYER_ID, PAID_AT);

            // assert
            assertAll(
                () -> assertStockQuantity(scenario.productA(), 3),
                () -> assertStockQuantity(scenario.productB(), 3),
                () -> assertPointBalance(scenario.buyer(), 3_000L)
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
            Scenario scenario = scenario(5, 4, 10_000L);

            // act
            scenario.confirm(BUYER_ID, PAID_AT);

            // assert
            assertThat(scenario.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }
    }

    @DisplayName("[INV-43] 결제액은 확정 시점의 주문 합계다.")
    @Nested
    class PaidAmountIsOrderTotal {

        @DisplayName("[동등 클래스 분할] 합계 7000원인 주문의 결제액은 7000원이다.")
        @Test
        void savesOrderTotalAsPaidAmount() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);

            // act
            scenario.confirm(BUYER_ID, PAID_AT);

            // assert
            assertPaymentResult(scenario.order(), TOTAL_AMOUNT, PAID_AT);
        }

        @DisplayName("[상태 전이] 확정 전에 품목 수량을 바꾸면 바뀐 합계가 결제액이 된다.")
        @Test
        void savesChangedTotalAsPaidAmount() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);
            scenario.order().changeItemQuantity(BUYER_ID, scenario.productA().getId(), 1);

            // act
            scenario.confirm(BUYER_ID, PAID_AT);

            // assert
            assertPaymentResult(scenario.order(), 5_000L, PAID_AT);
        }
    }

    @DisplayName("[INV-31] 주문은 구매자의 것이다.")
    @Nested
    class OwnedByBuyer {

        @DisplayName("[동등 클래스 분할] 다른 고객이 확정하면 없는 주문으로 거절하고, 아무것도 바뀌지 않는다.")
        @Test
        void throwsOrderNotFound_andKeepsAllState() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> scenario.confirm(OTHER_USER_ID, PAID_AT));

            // assert
            assertRejectedState(scenario, result, ErrorCode.ORDER_NOT_FOUND, 5, 4, 10_000L);
        }
    }

    @DisplayName("[INV-33] 주문에 쓰인 상품은 주문 생성 시점과 확정 시점 모두 존재하며 삭제되지 않은 상태다.")
    @Nested
    class ProductsAvailableAtConfirmation {

        @DisplayName("[의사결정표] 상품 하나가 삭제됐으면 판매 불가로 거절하고, 아무것도 바뀌지 않는다.")
        @Test
        void throwsProductNotAvailable_whenProductIsDeleted() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);
            scenario.productB().delete();

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> scenario.confirm(BUYER_ID, PAID_AT));

            // assert
            assertRejectedState(
                scenario, result, ErrorCode.PRODUCT_NOT_AVAILABLE, 5, 4, 10_000L);
        }

        @DisplayName("[의사결정표] 상품 하나가 없으면 판매 불가로 거절하고, 아무것도 바뀌지 않는다.")
        @Test
        void throwsProductNotAvailable_whenProductIsMissing() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);

            // act
            CoreException result = assertThrows(CoreException.class, () -> scenario.service()
                .confirm(
                    BUYER_ID,
                    scenario.order(),
                    List.of(scenario.productA()),
                    scenario.buyer(),
                    PAID_AT
                ));

            // assert
            assertRejectedState(
                scenario, result, ErrorCode.PRODUCT_NOT_AVAILABLE, 5, 4, 10_000L);
        }
    }

    @DisplayName("[INV-35] 거절된 확정은 주문 상태·재고·잔액을 바꾸지 않는다.")
    @Nested
    class KeepAllStateOnRejection {

        @DisplayName("[경계값 분석] 뒤쪽 상품의 재고가 1 모자라면 거절하고, 앞쪽 상품의 재고도 그대로다.")
        @Test
        void keepsEarlierProductStock_whenLaterProductLacksStock() {
            // arrange
            Scenario scenario = scenario(5, 0, 10_000L);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> scenario.confirm(BUYER_ID, PAID_AT));

            // assert
            assertRejectedState(scenario, result, ErrorCode.INSUFFICIENT_STOCK, 5, 0, 10_000L);
        }

        @DisplayName("[경계값 분석] 앞쪽 상품의 재고가 1 모자라면 거절하고, 뒤쪽 상품의 재고도 그대로다.")
        @Test
        void keepsLaterProductStock_whenEarlierProductLacksStock() {
            // arrange
            Scenario scenario = scenario(1, 4, 10_000L);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> scenario.confirm(BUYER_ID, PAID_AT));

            // assert
            assertRejectedState(scenario, result, ErrorCode.INSUFFICIENT_STOCK, 1, 4, 10_000L);
        }

        @DisplayName("[경계값 분석] 잔액이 합계보다 1 적으면 거절하고, 재고도 그대로다.")
        @Test
        void keepsStock_whenBalanceIsOneLessThanTotal() {
            // arrange
            Scenario scenario = scenario(5, 4, TOTAL_AMOUNT - 1);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> scenario.confirm(BUYER_ID, PAID_AT));

            // assert
            assertRejectedState(
                scenario, result, ErrorCode.INSUFFICIENT_POINT, 5, 4, TOTAL_AMOUNT - 1);
        }
    }

    @DisplayName("[INV-37] 확정된 주문의 결제 결과는 바뀌지 않는다.")
    @Nested
    class ImmutablePaymentResult {

        @DisplayName("[상태 전이] 재확정을 거절하고, 결제 결과·재고·잔액은 첫 확정 뒤 그대로다.")
        @Test
        void throwsOrderAlreadyConfirmed_andKeepsPaymentResult() {
            // arrange
            Scenario scenario = scenario(5, 4, 10_000L);
            scenario.confirm(BUYER_ID, PAID_AT);

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> scenario.confirm(BUYER_ID, PAID_AT.plusMinutes(1)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode())
                    .isEqualTo(ErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(scenario.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertPaymentResult(scenario.order(), TOTAL_AMOUNT, PAID_AT),
                () -> assertStockQuantity(scenario.productA(), 3),
                () -> assertStockQuantity(scenario.productB(), 3),
                () -> assertPointBalance(scenario.buyer(), 3_000L)
            );
        }
    }

    private static Scenario scenario(int stockA, int stockB, long balance) {
        Product productA = product(10L, "상품 A", 2_000L, stockA);
        Product productB = product(20L, "상품 B", 3_000L, stockB);
        User buyer = new User();
        buyer.charge(balance);
        Order order = new Order(BUYER_ID, List.of(
            OrderItem.of(productA, 2),
            OrderItem.of(productB, 1)
        ));
        return new Scenario(new OrderConfirmService(), productA, productB, buyer, order);
    }

    /**
     * 식별자는 JPA 가 저장할 때만 채워지므로, 상품 둘을 서로 다른 품목으로 만들려면 여기서 넣어야 한다.
     * 같은 식별자면 한 품목으로 합쳐져(INV-28) 상품 하나짜리 주문이 된다.
     */
    private static Product product(Long id, String name, long price, int stock) {
        Product product = new Product(1L, name, price);
        ReflectionTestUtils.setField(product, "id", id);
        product.changeStock(stock);
        return product;
    }

    private static void assertRejectedState(
        Scenario scenario,
        CoreException exception,
        ErrorCode expectedError,
        int expectedStockA,
        int expectedStockB,
        long expectedBalance
    ) {
        assertAll(
            () -> assertThat(exception.getErrorCode()).isEqualTo(expectedError),
            () -> assertThat(scenario.order().getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(scenario.order().getPaymentResult()).isNull(),
            () -> assertStockQuantity(scenario.productA(), expectedStockA),
            () -> assertStockQuantity(scenario.productB(), expectedStockB),
            () -> assertPointBalance(scenario.buyer(), expectedBalance)
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
        Product productA,
        Product productB,
        User buyer,
        Order order
    ) {
        void confirm(Long requesterId, ZonedDateTime paidAt) {
            service.confirm(requesterId, order, List.of(productA, productB), buyer, paidAt);
        }
    }
}
