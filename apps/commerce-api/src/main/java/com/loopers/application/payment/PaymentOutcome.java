package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderStatus;

/**
 * 결제 요청·확인의 결과. {@code settled}가 거짓이면 결과를 기다리거나 모르는 상태라서, 호출자에게는 HTTP 202로 알린다.
 * 결제가 끝났다(CONFIRMED·PAYMENT_FAILED)면 200이다.
 */
public record PaymentOutcome(OrderInfo order, boolean settled) {

    public static PaymentOutcome of(OrderInfo order) {
        boolean settled = order.status() == OrderStatus.CONFIRMED || order.status() == OrderStatus.PAYMENT_FAILED;
        return new PaymentOutcome(order, settled);
    }

    public PaymentOutcome withLookupUnavailable() {
        return new PaymentOutcome(order.withLookupUnavailable(), settled);
    }
}
