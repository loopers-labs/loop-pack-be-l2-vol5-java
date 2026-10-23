package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;

/** READY 는 결제 없는 DRAFT 일 뿐 발송 권한·재고 확보를 뜻하지 않는다. STORED 는 이미 저장된 결과다(설계 4.3). */
public record PrepareConfirmationResult(Status status, OrderInfo stored) {

    public enum Status {
        READY,
        STORED
    }

    static PrepareConfirmationResult ready() {
        return new PrepareConfirmationResult(Status.READY, null);
    }

    static PrepareConfirmationResult stored(OrderInfo order) {
        return new PrepareConfirmationResult(Status.STORED, order);
    }
}
