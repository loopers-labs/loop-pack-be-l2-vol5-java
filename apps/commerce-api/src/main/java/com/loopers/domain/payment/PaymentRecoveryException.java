package com.loopers.domain.payment;

/** Recovery availability is separate from a definitive payment failure. */
public class PaymentRecoveryException extends RuntimeException {
    private final Reason reason;

    public PaymentRecoveryException(Reason reason) {
        super("Payment recovery could not complete: " + reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        PAYMENT_NOT_STARTED,
        PAYMENT_ATTEMPT_NOT_FOUND,
        PAYMENT_RESULT_UNAVAILABLE
    }
}
