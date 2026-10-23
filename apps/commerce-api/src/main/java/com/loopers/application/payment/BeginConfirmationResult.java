package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentRequest;

/**
 * SEND 는 이번 실행의 발송 권한이다. TX A 가 프록시 commit 까지 성공한 뒤에만 호출자가 받으며 PG 생성을 한 번 보낸다.
 * STORED 는 다른 실행이 이미 만든 결과라 생성·조회·재고 효과가 없다(설계 4.3).
 */
public record BeginConfirmationResult(Status status, PaymentRequest request, PaymentCheckToken token, OrderInfo stored) {

    public enum Status {
        SEND,
        STORED
    }

    static BeginConfirmationResult send(PaymentRequest request, PaymentCheckToken token) {
        return new BeginConfirmationResult(Status.SEND, request, token, null);
    }

    static BeginConfirmationResult stored(OrderInfo order) {
        return new BeginConfirmationResult(Status.STORED, null, null, order);
    }
}
