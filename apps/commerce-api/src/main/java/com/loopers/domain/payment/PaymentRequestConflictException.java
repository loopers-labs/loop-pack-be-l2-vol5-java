package com.loopers.domain.payment;

/** A conflicting attempt or request, not a PG failure or an unavailable comparison. */
public class PaymentRequestConflictException extends RuntimeException {
    public PaymentRequestConflictException() {
        super("Payment request conflicts with an existing attempt");
    }
}
