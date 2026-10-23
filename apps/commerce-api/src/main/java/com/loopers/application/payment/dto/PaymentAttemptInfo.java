package com.loopers.application.payment.dto;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;

/**
 * 결제 시도의 현재 상태를 주문 상태와 함께 담은 값. 엔티티가 아니라 값이므로 트랜잭션 밖에서 들고 다녀도 안전하다.
 * {@code settled} 가 true 이면 PG 결과로 종결한 시도이고, 아니면 처리 중이거나 결과 미확인이다.
 */
public record PaymentAttemptInfo(
    Long orderId,
    OrderStatus orderStatus,
    PaymentAttemptStatus attemptStatus,
    long amount,
    String providerOrderId,
    String transactionKey
) {
    public static PaymentAttemptInfo of(Order order, PaymentAttempt attempt) {
        return new PaymentAttemptInfo(
            order.getId(),
            order.getStatus(),
            attempt.getStatus(),
            attempt.getAmount().amount(),
            attempt.getProviderOrderId(),
            attempt.getTransactionKey()
        );
    }

    public boolean settled() {
        return attemptStatus.isTerminal();
    }
}
