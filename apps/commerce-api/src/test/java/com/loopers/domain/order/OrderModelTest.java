package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderModelTest {

    private OrderModel newDraftOrder() {
        return new OrderModel(1L, List.of(new OrderItemModel(10L, 2, 10_000L)));
    }

    @DisplayName("DRAFT 상태에서 startPayment()를 호출하면 PAYMENT_PENDING이 된다.")
    @Test
    void startPayment_transitionsToPending() {
        OrderModel order = newDraftOrder();

        order.startPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @DisplayName("PAYMENT_PENDING 상태에서 confirmPayment()를 호출하면 CONFIRMED가 된다.")
    @Test
    void confirmPayment_transitionsToConfirmed() {
        OrderModel order = newDraftOrder();
        order.startPayment();

        order.confirmPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(order.getTotalAmount());
    }

    @DisplayName("PAYMENT_PENDING 상태에서 failPayment()를 호출하면 PAYMENT_FAILED가 된다.")
    @Test
    void failPayment_transitionsToFailed() {
        OrderModel order = newDraftOrder();
        order.startPayment();

        order.failPayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @DisplayName("DRAFT가 아닌 상태에서 startPayment()를 호출하면 CONFLICT 예외가 발생한다.")
    @Test
    void startPayment_throwsWhenNotDraft() {
        OrderModel order = newDraftOrder();
        order.startPayment();

        assertThatThrownBy(order::startPayment)
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
    }

    @DisplayName("PAYMENT_PENDING이 아닌 상태에서 confirmPayment()를 호출하면 CONFLICT 예외가 발생한다.")
    @Test
    void confirmPayment_throwsWhenNotPending() {
        OrderModel order = newDraftOrder();

        assertThatThrownBy(order::confirmPayment)
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
    }

    @DisplayName("DRAFT 상태에서 expire()를 호출하면 EXPIRED가 된다.")
    @Test
    void expire_transitionsToExpired() {
        OrderModel order = newDraftOrder();

        order.expire();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }
}
