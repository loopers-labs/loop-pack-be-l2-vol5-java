package com.loopers.domain.purchase;

public class OrderConfirmationConflictException extends IllegalStateException {
    public OrderConfirmationConflictException(String message) {
        super(message);
    }
}
