package com.loopers.domain.ordering.model;

public class OrderConfirmationConflictException extends RuntimeException {
    public OrderConfirmationConflictException(OrderConfirmation confirmation) {
        super("저장된 확정 사실과 충돌합니다. eventId=%s orderId=%d"
            .formatted(confirmation.eventId(), confirmation.orderId()));
    }
}
