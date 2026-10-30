package com.loopers.domain.purchase;

/** 같은 eventId·orderId 로 이미 저장된 사실과 다른 내용이 왔다. 정상 재전달이 아니라 계약 위반이므로 효과를 더하지 않는다. */
public class OrderConfirmationConflictException extends RuntimeException {

    public OrderConfirmationConflictException(String message) {
        super(message);
    }
}
