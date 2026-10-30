package com.loopers.purchaseactivity.domain;

public class ConfirmationConflictException extends RuntimeException {
    public ConfirmationConflictException(String eventId, long orderId) {
        super("Order confirmation conflicts with stored facts: eventId=" + eventId + ", orderId=" + orderId);
    }
}
