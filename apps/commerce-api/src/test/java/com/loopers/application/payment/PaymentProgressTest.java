package com.loopers.application.payment;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProgressTest {

    @DisplayName("주문 상태가 DRAFT·CONFIRMED·PAYMENT_FAILED이면 시도와 충돌에 관계없이 미결제·완료·실패다.")
    @Test
    void followsOrderStatusFirst() {
        assertThat(PaymentProgress.of(OrderStatus.DRAFT, null, null)).isEqualTo(PaymentProgress.NOT_STARTED);
        assertThat(PaymentProgress.of(OrderStatus.CONFIRMED, null, null)).isEqualTo(PaymentProgress.COMPLETED);
        assertThat(PaymentProgress.of(OrderStatus.CONFIRMED, PaymentAttemptStatus.SUCCEEDED,
            PaymentConflictReason.RESULT_CONTRADICTION)).isEqualTo(PaymentProgress.COMPLETED);
        assertThat(PaymentProgress.of(OrderStatus.PAYMENT_FAILED, PaymentAttemptStatus.FAILED, null))
            .isEqualTo(PaymentProgress.FAILED);
    }

    @DisplayName("결제 대기 주문은 PG가 접수를 확인한 PENDING이고 충돌이 없을 때만 결제 대기다.")
    @Test
    void showsWaitingOnlyForAcceptedAttemptWithoutConflict() {
        assertThat(PaymentProgress.of(OrderStatus.PAYMENT_PENDING, PaymentAttemptStatus.PENDING, null))
            .isEqualTo(PaymentProgress.WAITING);
        assertThat(PaymentProgress.of(OrderStatus.PAYMENT_PENDING, PaymentAttemptStatus.PENDING,
            PaymentConflictReason.TRANSACTION_MISMATCH)).isEqualTo(PaymentProgress.UNCONFIRMED);
    }

    @DisplayName("결제 대기 주문의 REQUESTED·UNKNOWN 시도는 결과 미확인이다.")
    @ParameterizedTest
    @EnumSource(value = PaymentAttemptStatus.class, names = {"REQUESTED", "UNKNOWN"})
    void showsUnconfirmedBeforeAcceptance(PaymentAttemptStatus status) {
        assertThat(PaymentProgress.of(OrderStatus.PAYMENT_PENDING, status, null)).isEqualTo(PaymentProgress.UNCONFIRMED);
    }
}
