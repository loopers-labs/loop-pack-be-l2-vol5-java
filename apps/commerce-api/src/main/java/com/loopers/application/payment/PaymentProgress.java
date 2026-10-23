package com.loopers.application.payment;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;

// 고객에게 보이는 결제 진행 표시다. 주문 상태를 먼저 보고, 결제 대기 주문만 PG의 거래 접수 확인 여부로 나눈다.
public enum PaymentProgress {
    NOT_STARTED,
    WAITING,
    UNCONFIRMED,
    COMPLETED,
    FAILED;

    public static PaymentProgress of(OrderStatus orderStatus, PaymentAttemptStatus attemptStatus,
                                     PaymentConflictReason conflictReason) {
        return switch (orderStatus) {
            case DRAFT -> NOT_STARTED;
            case CONFIRMED -> COMPLETED;
            case PAYMENT_FAILED -> FAILED;
            case PAYMENT_PENDING -> attemptStatus == PaymentAttemptStatus.PENDING && conflictReason == null
                ? WAITING
                : UNCONFIRMED;
        };
    }
}
