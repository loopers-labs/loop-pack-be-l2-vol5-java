package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    private static final long MAX_PRICE = 1_000_000_000L;

    private OrderProduct product(String name, long price) {
        return new OrderProduct(name, new Money(price));
    }

    private OrderLines lines(OrderLine... lines) {
        return new OrderLines(List.of(lines));
    }

    private void assertErrorType(ErrorType expected, Runnable action) {
        CoreException exception = assertThrows(CoreException.class, action::run);
        assertThat(exception.getErrorType()).isEqualTo(expected);
    }

    @DisplayName("주문을 생성할 때,")
    @Nested
    class Create {
        @DisplayName("DRAFT 상태이고, 품목마다 주문 당시 상품명·단가와 금액(단가 × 수량)을 가지며, 합계는 품목 금액의 합이다.")
        @Test
        void createsDraft_withSnapshotItemsAndTotal() {
            // arrange: 4-1 시나리오. 3,000 × 1 + 2,000 × 2 = 7,000
            Map<Long, OrderProduct> products = Map.of(
                10L, product("티셔츠", 3_000),
                20L, product("양말", 2_000)
            );

            // act
            Order order = Order.create(7L, lines(new OrderLine(10L, 1), new OrderLine(20L, 2)), products);

            // assert
            assertThat(order.getUserId()).isEqualTo(7L);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getTotalAmount()).isEqualTo(new Money(7_000));
            assertThat(order.getItems()).hasSize(2);

            OrderItem first = order.getItems().get(0);
            assertThat(first.getProductId()).isEqualTo(10L);
            assertThat(first.getProductName()).isEqualTo("티셔츠");
            assertThat(first.getQuantity()).isEqualTo(1);
            assertThat(first.getUnitPrice()).isEqualTo(new Money(3_000));
            assertThat(first.getAmount()).isEqualTo(new Money(3_000));

            OrderItem second = order.getItems().get(1);
            assertThat(second.getProductId()).isEqualTo(20L);
            assertThat(second.getProductName()).isEqualTo("양말");
            assertThat(second.getQuantity()).isEqualTo(2);
            assertThat(second.getUnitPrice()).isEqualTo(new Money(2_000));
            assertThat(second.getAmount()).isEqualTo(new Money(4_000));
        }

        @DisplayName("품목의 상품 id 를 품목 순서대로 돌려준다.")
        @Test
        void returnsProductIds_inItemOrder() {
            // arrange
            Order order = Order.create(
                7L,
                lines(new OrderLine(20L, 1), new OrderLine(10L, 2), new OrderLine(30L, 1)),
                Map.of(10L, product("티셔츠", 3_000), 20L, product("양말", 2_000), 30L, product("모자", 5_000))
            );

            // act & assert
            assertThat(order.productIds()).containsExactly(20L, 10L, 30L);
        }

        @DisplayName("요청자가 없으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void throwsInvalidInput_whenUserIdIsNull() {
            assertErrorType(ErrorType.INVALID_INPUT, () ->
                Order.create(null, lines(new OrderLine(10L, 1)), Map.of(10L, product("티셔츠", 3_000))));
        }

        @DisplayName("품목에 해당하는 상품이 주어지지 않으면 호출 오류이므로 INTERNAL_ERROR 로 거절한다.")
        @Test
        void throwsInternalError_whenProductIsMissing() {
            assertErrorType(ErrorType.INTERNAL_ERROR, () ->
                Order.create(7L, lines(new OrderLine(10L, 1), new OrderLine(20L, 1)), Map.of(10L, product("티셔츠", 3_000))));
        }

        @DisplayName("합계가 금액 표현 범위를 넘으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void throwsInvalidInput_whenTotalOverflows() {
            // arrange: 품목 하나는 최대 단가 × Integer.MAX_VALUE ≈ 2.1e18 로 long 범위 안이지만, 다섯 개를 더하면 9.2e18 을 넘는다.
            Map<Long, OrderProduct> products = Map.of(
                1L, product("가", MAX_PRICE),
                2L, product("나", MAX_PRICE),
                3L, product("다", MAX_PRICE),
                4L, product("라", MAX_PRICE),
                5L, product("마", MAX_PRICE)
            );
            OrderLines lines = lines(
                new OrderLine(1L, Integer.MAX_VALUE),
                new OrderLine(2L, Integer.MAX_VALUE),
                new OrderLine(3L, Integer.MAX_VALUE),
                new OrderLine(4L, Integer.MAX_VALUE),
                new OrderLine(5L, Integer.MAX_VALUE)
            );

            // act & assert
            assertErrorType(ErrorType.INVALID_INPUT, () -> Order.create(7L, lines, products));
        }
    }

    @DisplayName("주문을 확정할 때,")
    @Nested
    class Confirm {
        private Order draftOrder() {
            return Order.create(
                7L,
                lines(new OrderLine(10L, 1), new OrderLine(20L, 2)),
                Map.of(10L, product("티셔츠", 3_000), 20L, product("양말", 2_000))
            );
        }

        // 합계 7,000원을 결제한 결과
        private PaymentResult payment() {
            return new PaymentResult(new Money(7_000));
        }

        @DisplayName("확정하면 CONFIRMED 가 되고, 결제액을 보존한다. 품목과 합계는 그대로다.")
        @Test
        void becomesConfirmed_withPaymentResult() {
            // arrange
            Order order = draftOrder();

            // act
            order.confirm(payment());

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentResult()).isEqualTo(payment());
            assertThat(order.getTotalAmount()).isEqualTo(new Money(7_000));
            assertThat(order.getItems()).hasSize(2);
        }

        @DisplayName("이미 확정된 주문은 ORDER_ALREADY_CONFIRMED 로 거절하고, 기존 결제 결과를 유지한다.")
        @Test
        void rejectsWithAlreadyConfirmed_andKeepsPaymentResult_whenAlreadyConfirmed() {
            // arrange
            Order order = draftOrder();
            order.confirm(payment());
            PaymentResult other = new PaymentResult(new Money(1));

            // act & assert
            assertErrorType(ErrorType.ORDER_ALREADY_CONFIRMED, order::ensureConfirmable);
            assertErrorType(ErrorType.ORDER_ALREADY_CONFIRMED, () -> order.confirm(other));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentResult()).isEqualTo(payment());
        }

        @DisplayName("결제 결과가 없으면 INVALID_INPUT 으로 거절하고, DRAFT 를 유지한다.")
        @Test
        void rejectsWithInvalidInput_andStaysDraft_whenPaymentResultIsNull() {
            // arrange
            Order order = draftOrder();

            // act & assert
            assertErrorType(ErrorType.INVALID_INPUT, () -> order.confirm(null));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaymentResult()).isNull();
        }
    }

    @DisplayName("PG 결제를 진행할 때,")
    @Nested
    class Payment {
        private Order draftOrder() {
            return Order.create(
                7L,
                lines(new OrderLine(10L, 1), new OrderLine(20L, 2)),
                Map.of(10L, product("티셔츠", 3_000), 20L, product("양말", 2_000))
            );
        }

        private Order pendingOrder() {
            Order order = draftOrder();
            order.markPaymentPending();
            return order;
        }

        private PaymentResult paid() {
            return new PaymentResult(new Money(7_000));
        }

        @DisplayName("결제를 시작하면 DRAFT 가 PAYMENT_PENDING 이 되고, 결제 결과는 아직 없다.")
        @Test
        void becomesPaymentPending_withoutPaymentResult() {
            // arrange
            Order order = draftOrder();

            // act
            order.markPaymentPending();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentResult()).isNull();
        }

        @DisplayName("이미 CONFIRMED 인 주문은 ORDER_ALREADY_CONFIRMED 로, 진행 중이거나 실패한 주문은 CONFLICT 로 시작을 거절한다.")
        @Test
        void rejectsStart_whenNotDraft() {
            // arrange
            Order confirmed = pendingOrder();
            confirmed.completePayment(paid());
            Order pending = pendingOrder();
            Order failed = pendingOrder();
            failed.failPayment();

            // act & assert
            assertErrorType(ErrorType.ORDER_ALREADY_CONFIRMED, confirmed::markPaymentPending);
            assertErrorType(ErrorType.CONFLICT, pending::markPaymentPending);
            assertErrorType(ErrorType.CONFLICT, failed::markPaymentPending);
            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(pending.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        }

        @DisplayName("성공을 반영하면 CONFIRMED 가 되고, 결제액을 보존한다. 품목과 합계는 그대로다.")
        @Test
        void becomesConfirmed_withPgPaymentResult() {
            // arrange
            Order order = pendingOrder();

            // act
            order.completePayment(paid());

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaymentResult()).isEqualTo(paid());
            assertThat(order.getTotalAmount()).isEqualTo(new Money(7_000));
            assertThat(order.getItems()).hasSize(2);
        }

        @DisplayName("확정 실패를 반영하면 PAYMENT_FAILED 가 되고, 결제 결과는 없다.")
        @Test
        void becomesPaymentFailed_withoutPaymentResult() {
            // arrange
            Order order = pendingOrder();

            // act
            order.failPayment();

            // assert
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(order.getPaymentResult()).isNull();
        }

        @DisplayName("결제 대기가 아닌 주문의 성공·실패 반영은 CONFLICT 로 거절하고, 상태와 결제 결과를 유지한다.")
        @Test
        void rejectsCompleteAndFail_whenNotPaymentPending() {
            // arrange
            Order draft = draftOrder();
            Order confirmed = pendingOrder();
            confirmed.completePayment(paid());
            Order failed = pendingOrder();
            failed.failPayment();
            PaymentResult other = new PaymentResult(new Money(1));

            // act & assert
            assertErrorType(ErrorType.CONFLICT, () -> draft.completePayment(paid()));
            assertErrorType(ErrorType.CONFLICT, draft::failPayment);
            assertErrorType(ErrorType.CONFLICT, () -> confirmed.completePayment(other));
            assertErrorType(ErrorType.CONFLICT, confirmed::failPayment);
            assertErrorType(ErrorType.CONFLICT, () -> failed.completePayment(paid()));
            assertErrorType(ErrorType.CONFLICT, failed::failPayment);
            assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.getPaymentResult()).isEqualTo(paid());
            assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(failed.getPaymentResult()).isNull();
        }

        @DisplayName("결제 결과가 없으면 INVALID_INPUT 으로 거절하고, PAYMENT_PENDING 을 유지한다.")
        @Test
        void rejectsWithInvalidInput_andStaysPending_whenPaymentResultIsNull() {
            // arrange
            Order order = pendingOrder();

            // act & assert
            assertErrorType(ErrorType.INVALID_INPUT, () -> order.completePayment(null));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentResult()).isNull();
        }
    }
}
