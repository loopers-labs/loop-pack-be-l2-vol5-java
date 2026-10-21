package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.ProductId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {
    private OrderItem item(long id, int quantity, long price) {
        return new OrderItem(new ProductId(id), new Quantity(quantity), new Money(price));
    }
    @Test
    @DisplayName("여러 품목 금액을 합산한 DRAFT 주문은 결제를 시작해 성공하면 CONFIRMED가 되고 다시 결제를 시작하면 거절한다")
    void confirmsAndRejectsRepeatedConfirmation() {
        Order order = Order.create(1, List.of(item(1, 2, 2000), item(2, 1, 3000)));
        assertThat(order.getTotal()).isEqualTo(new Money(7000));
        assertThat(order.getStatus()).isEqualTo(Order.Status.DRAFT);
        order.startPayment();
        order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED);
        assertThat(order.getStatus()).isEqualTo(Order.Status.CONFIRMED);
        assertThatThrownBy(order::startPayment).isInstanceOf(IllegalStateException.class);
    }
    @Test
    @DisplayName("빈 주문과 중복 상품 품목은 거절한다")
    void rejectsEmptyAndDuplicateItems() {
        assertThatThrownBy(() -> Order.create(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(1, List.of(item(1, 1, 100), item(1, 2, 100))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    @DisplayName("주문 품목은 외부에서 변경할 수 없고 0원 주문도 확정할 수 있다")
    void protectsItemsAndAllowsZeroTotal() {
        Order order = Order.create(1, List.of(item(1, 1, 0)));
        assertThatThrownBy(() -> order.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(order.getTotal()).isEqualTo(new Money(0));
        order.startPayment();
        order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED);
        assertThat(order.getStatus()).isEqualTo(Order.Status.CONFIRMED);
    }

    @Test
    @DisplayName("DRAFT 주문의 결제를 시작하면 결제 대기가 되고, 결제 대기·종결 주문의 결제 시작은 거절한다")
    void startsPaymentOnlyFromDraft() {
        Order order = Order.create(1, List.of(item(1, 1, 1000)));

        order.startPayment();

        assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_PENDING);
        assertThatThrownBy(order::startPayment).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(Order.Status.PAYMENT_PENDING);
        for (Order.Status closed : List.of(Order.Status.CONFIRMED, Order.Status.PAYMENT_FAILED)) {
            Order restored = Order.restore(1, 1, List.of(item(1, 1, 1000)), closed);
            assertThatThrownBy(restored::startPayment).isInstanceOf(IllegalStateException.class);
            assertThat(restored.getStatus()).isEqualTo(closed);
        }
    }

    @ParameterizedTest(name = "{0} 주문에 결과 {1}가 오면 {2}, 이후 상태 {3}")
    @CsvSource({
        "DRAFT, SUCCEEDED, ANOMALY, DRAFT",
        "DRAFT, FAILED, ANOMALY, DRAFT",
        "DRAFT, UNKNOWN, ANOMALY, DRAFT",
        "PAYMENT_PENDING, SUCCEEDED, CHANGED, CONFIRMED",
        "PAYMENT_PENDING, FAILED, CHANGED, PAYMENT_FAILED",
        "PAYMENT_PENDING, UNKNOWN, UNCHANGED, PAYMENT_PENDING",
        "CONFIRMED, SUCCEEDED, UNCHANGED, CONFIRMED",
        "CONFIRMED, FAILED, CONFLICT, CONFIRMED",
        "CONFIRMED, UNKNOWN, UNCHANGED, CONFIRMED",
        "PAYMENT_FAILED, SUCCEEDED, CONFLICT, PAYMENT_FAILED",
        "PAYMENT_FAILED, FAILED, UNCHANGED, PAYMENT_FAILED",
        "PAYMENT_FAILED, UNKNOWN, UNCHANGED, PAYMENT_FAILED"
    })
    @DisplayName("결제 결과 반영은 결제 대기 주문만 바꾸고 다른 상태는 덮어쓰지 않는다(설계 23절 상태 전이 표)")
    void appliesPaymentOutcomeByTransitionTable(Order.Status current, Order.PaymentOutcome outcome,
        Order.PaymentTransition expectedTransition, Order.Status expectedStatus) {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), current);

        Order.PaymentTransition transition = order.applyPaymentOutcome(outcome);

        assertThat(transition).isEqualTo(expectedTransition);
        assertThat(order.getStatus()).isEqualTo(expectedStatus);
    }
}
