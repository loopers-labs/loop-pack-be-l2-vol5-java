package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.ProductId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {
    private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-28T01:00:00Z");

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
        order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, NOW);
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
        order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, NOW);
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
        for (Order.Status closed : List.of(Order.Status.CONFIRMED, Order.Status.PAYMENT_FAILED, Order.Status.PAYMENT_EXPIRED)) {
            Order restored = Order.restore(1, 1, List.of(item(1, 1, 1000)), closed, null);
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
        "PAYMENT_FAILED, UNKNOWN, UNCHANGED, PAYMENT_FAILED",
        "PAYMENT_EXPIRED, SUCCEEDED, CONFLICT, PAYMENT_EXPIRED",
        "PAYMENT_EXPIRED, FAILED, UNCHANGED, PAYMENT_EXPIRED",
        "PAYMENT_EXPIRED, UNKNOWN, UNCHANGED, PAYMENT_EXPIRED"
    })
    @DisplayName("결제 결과 반영은 결제 대기 주문만 바꾸고 다른 상태는 덮어쓰지 않는다(설계 23절 상태 전이 표)")
    void appliesPaymentOutcomeByTransitionTable(Order.Status current, Order.PaymentOutcome outcome,
        Order.PaymentTransition expectedTransition, Order.Status expectedStatus) {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), current, null);

        Order.PaymentTransition transition = order.applyPaymentOutcome(outcome, NOW);

        assertThat(transition).isEqualTo(expectedTransition);
        assertThat(order.getStatus()).isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "{0} 주문 만료 → 전이={1}, 이후 상태 {2}")
    @CsvSource({
        "DRAFT, false, DRAFT",
        "PAYMENT_PENDING, true, PAYMENT_EXPIRED",
        "CONFIRMED, false, CONFIRMED",
        "PAYMENT_FAILED, false, PAYMENT_FAILED",
        "PAYMENT_EXPIRED, false, PAYMENT_EXPIRED"
    })
    @DisplayName("결제 만료는 결제 대기 주문만 결제 만료로 바꾸고 다른 상태는 그대로 둔다(설계 26절)")
    void expiresOnlyPendingPayment(Order.Status current, boolean expectedChanged, Order.Status expectedStatus) {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), current, null);

        boolean changed = order.expirePayment();

        assertThat(changed).isEqualTo(expectedChanged);
        assertThat(order.getStatus()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("결제 대기 주문이 처음 CONFIRMED가 되면 확정 시각을 반영 시각으로 남기고 결제 시작 전·결제 대기 주문은 확정 시각이 없다")
    void recordsConfirmedAtOnFirstConfirmation() {
        Order order = Order.create(1, List.of(item(1, 1, 1000)));
        assertThat(order.getConfirmedAt()).isNull();
        order.startPayment();
        assertThat(order.getConfirmedAt()).isNull();

        Order.PaymentTransition transition = order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, NOW);

        assertThat(transition).isEqualTo(Order.PaymentTransition.CHANGED);
        assertThat(order.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("확정 시각은 밀리초로 잘라 남겨 DB에 저장했다 다시 읽은 값과 같다")
    void truncatesConfirmedAtToMillis() {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), Order.Status.PAYMENT_PENDING, null);

        order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, NOW.plusNanos(123_456_789));

        assertThat(order.getConfirmedAt()).isEqualTo(NOW.plusMillis(123));
    }

    @ParameterizedTest(name = "CONFIRMED 주문에 결과 {0}")
    @EnumSource(Order.PaymentOutcome.class)
    @DisplayName("이미 CONFIRMED인 주문은 어떤 결과가 와도 CHANGED가 아니고 상태·확정 시각이 그대로다(eventId 전제: 확정은 한 번)")
    void confirmedOrderNeverChangesAgain(Order.PaymentOutcome outcome) {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), Order.Status.CONFIRMED, EARLIER);

        Order.PaymentTransition transition = order.applyPaymentOutcome(outcome, NOW);

        assertThat(transition).isNotEqualTo(Order.PaymentTransition.CHANGED);
        assertThat(order.getStatus()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(EARLIER);
    }

    @Test
    @DisplayName("확정 시각이 없는 옛 CONFIRMED 주문에 성공이 다시 와도 UNCHANGED이고 확정 시각을 소급해 채우지 않는다")
    void legacyConfirmedOrderKeepsNullConfirmedAt() {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), Order.Status.CONFIRMED, null);

        Order.PaymentTransition transition = order.applyPaymentOutcome(Order.PaymentOutcome.SUCCEEDED, NOW);

        assertThat(transition).isEqualTo(Order.PaymentTransition.UNCHANGED);
        assertThat(order.getConfirmedAt()).isNull();
    }

    @ParameterizedTest(name = "{0} 주문에 결과 {1}")
    @CsvSource({
        "PAYMENT_PENDING, FAILED",
        "PAYMENT_PENDING, UNKNOWN",
        "DRAFT, SUCCEEDED",
        "PAYMENT_FAILED, SUCCEEDED",
        "PAYMENT_EXPIRED, SUCCEEDED"
    })
    @DisplayName("CONFIRMED로 바뀌지 않는 결과 반영은 확정 시각을 남기지 않는다")
    void otherTransitionsDoNotRecordConfirmedAt(Order.Status current, Order.PaymentOutcome outcome) {
        Order order = Order.restore(1, 1, List.of(item(1, 1, 1000)), current, null);

        order.applyPaymentOutcome(outcome, NOW);

        assertThat(order.getConfirmedAt()).isNull();
    }
}
