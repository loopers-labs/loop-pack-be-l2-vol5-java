package com.loopers.application.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;

// 주문의 결제 상태 객체다. 시도가 없는 DRAFT·이전 POINT 주문은 시도 필드가 비어 있다.
// 거래 키·충돌 사유는 관리자 응답에서만 노출하고, 고객 응답 변환에서 제외한다.
public record PaymentStatusInfo(Long orderId, OrderStatus orderStatus, PaymentMethod paymentMethod,
                                PaymentProgress paymentProgress, Long attemptId, PaymentAttemptStatus attemptStatus,
                                String transactionKey, PaymentConflictReason conflictReason,
                                PaymentResult paymentResult, Long paymentAmount) {

    public static PaymentStatusInfo from(Order order, PaymentAttempt attempt) {
        PaymentAttemptStatus attemptStatus = attempt == null ? null : attempt.getStatus();
        PaymentConflictReason conflictReason = attempt == null ? null : attempt.getConflictReason();
        return new PaymentStatusInfo(
            order.getId(), order.getStatus(), order.getPaymentMethod(),
            PaymentProgress.of(order.getStatus(), attemptStatus, conflictReason),
            attempt == null ? null : attempt.getId(), attemptStatus,
            attempt == null ? null : attempt.getTransactionKey(), conflictReason,
            order.getPaymentResult(), order.getPaymentAmount()
        );
    }

    // confirm·reconcile은 결제 대기 주문이면 202, 종결된 주문이면 200으로 응답한다.
    public boolean isUnterminated() {
        return orderStatus == OrderStatus.PAYMENT_PENDING;
    }
}
