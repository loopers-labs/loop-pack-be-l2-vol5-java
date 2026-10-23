package com.loopers.domain.payment;

/** External observation conflict, distinct from a changed customer payment request. */
public class PaymentResultConflictException extends RuntimeException {
    public PaymentResultConflictException() {
        super("External payment result conflicts with stored payment facts");
    }
}
