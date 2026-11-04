package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmationConflictException;

/** 같은 eventId 또는 orderId로 저장된 사실과 다른 메시지. 수신 계층이 DLQ로 분리할 수 있게 드러낸다. */
public class ConflictingOrderConfirmationException extends RuntimeException {
    public ConflictingOrderConfirmationException(OrderConfirmationConflictException cause) {
        super(cause.getMessage(), cause);
    }
}
