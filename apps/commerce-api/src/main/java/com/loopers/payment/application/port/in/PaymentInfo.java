package com.loopers.payment.application.port.in;

import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;

/**
 * 주문의 결제 시도 상태 (plan.md 8). 카드 정보는 없다.
 */
public record PaymentInfo(
    Long paymentId,
    Long orderId,
    OrderStatus orderStatus,
    PaymentStatus paymentStatus,
    String failureReason,
    String transactionKey
) {
    public static PaymentInfo of(OrderModel order, Payment payment) {
        return new PaymentInfo(
            payment.getId(),
            order.getId(),
            order.getStatus(),
            payment.getStatus(),
            payment.getFailureReason(),
            payment.getTransactionKey()
        );
    }

    /**
     * 결제 결과가 정해졌는지 (SUCCESS·FAILED). 아직이면 클라이언트는 나중에 다시 조회한다.
     */
    public boolean isFinal() {
        return paymentStatus != null && paymentStatus.isFinal();
    }
}
