package com.loopers.domain.purchase;

public class OrderConfirmationConflictException extends RuntimeException {
    public OrderConfirmationConflictException(String eventId, Long orderId) {
        super("저장된 주문 확정 사실과 충돌합니다: eventId=" + eventId + ", orderId=" + orderId);
    }
}
