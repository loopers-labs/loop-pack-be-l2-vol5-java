package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Order 는 PG 결제 시작·확정·실패 전이와 결제 방식·금액 규칙을 책임진다.")
class OrderPgPaymentTest {

    private OrderModel draft() {
        return OrderModel.draft(1L, List.of(
            OrderItemModel.of(1L, 2L, Money.of(2_000L)), OrderItemModel.of(2L, 1L, Money.of(3_000L))));
    }

    private OrderModel pending() {
        OrderModel order = draft();
        order.startPgPayment();
        return order;
    }

    @DisplayName("DRAFT 의 결제 방식·사용액·결제액은 비어 있다.")
    @Test
    void draftHasNoPayment() {
        OrderModel order = draft();

        assertAll(
            () -> assertThat(order.getPaymentMethod()).isNull(),
            () -> assertThat(order.getPointAmount()).isNull(),
            () -> assertThat(order.getPaidAmount()).isNull(),
            () -> assertThat(order.getTotalAmount()).isEqualTo(Money.of(7_000L))
        );
    }

    @DisplayName("PG 결제를 시작하면 PG·포인트 사용액 0·PAYMENT_PENDING 이고 결제액은 비어 있다.")
    @Test
    void startsPgPayment() {
        OrderModel order = pending();

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
            () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG),
            () -> assertThat(order.getPointAmount()).isZero(),
            () -> assertThat(order.getPaidAmount()).isNull()
        );
    }

    @DisplayName("DRAFT 가 아니면 PG 결제를 시작할 수 없다.")
    @Test
    void rejectsStartUnlessDraft() {
        OrderModel order = pending();

        assertThatThrownBy(order::startPgPayment)
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE);
    }

    @DisplayName("주문 총액과 같은 성공 금액이면 결제액을 기록하고 CONFIRMED 다.")
    @Test
    void confirmsWithSameAmount() {
        OrderModel order = pending();

        order.confirmPg(Money.of(7_000L));

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000L)),
            () -> assertThat(order.getPointAmount()).isZero()
        );
    }

    @DisplayName("성공 금액이 총액과 다르면 내부 오류이고 상태를 바꾸지 않는다.")
    @Test
    void rejectsDifferentAmount() {
        OrderModel order = pending();

        assertThatThrownBy(() -> order.confirmPg(Money.of(6_999L)))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @DisplayName("실패 확정이면 결제액 없이 PAYMENT_FAILED 다.")
    @Test
    void failsPg() {
        OrderModel order = pending();

        order.failPg();

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED),
            () -> assertThat(order.getPaidAmount()).isNull()
        );
    }

    @DisplayName("PG 결제 대기가 아니면 확정·실패 전이는 내부 오류다.")
    @Test
    void rejectsTransitionsOutsidePending() {
        OrderModel draft = draft();
        OrderModel confirmed = pending();
        confirmed.confirmPg(Money.of(7_000L));

        assertAll(
            () -> assertThatThrownBy(() -> draft.confirmPg(Money.of(7_000L)))
                .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(draft::failPg).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(confirmed::failPg).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
        );
    }

    @DisplayName("기존 포인트 확정은 결제 방식을 POINT 로 남긴다.")
    @Test
    void pointConfirmationKeepsPointMethod() {
        OrderModel order = draft();

        order.confirmWithPoints(7_000L);

        assertAll(
            () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT),
            () -> assertThat(order.getPointAmount()).isEqualTo(7_000L),
            () -> assertThat(order.getPaidAmount()).isEqualTo(Money.of(7_000L))
        );
    }
}
