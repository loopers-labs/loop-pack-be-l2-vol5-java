package com.loopers.domain.payment;

public record PaymentOutcome(Effect effect, PaymentDiscrepancy discrepancy) {

    public enum Effect {
        NONE,
        CONFIRM_ORDER,
        FAIL_ORDER
    }

    static PaymentOutcome none() {
        return new PaymentOutcome(Effect.NONE, null);
    }

    static PaymentOutcome confirmOrder() {
        return new PaymentOutcome(Effect.CONFIRM_ORDER, null);
    }

    static PaymentOutcome failOrder() {
        return new PaymentOutcome(Effect.FAIL_ORDER, null);
    }

    static PaymentOutcome conflict(PaymentDiscrepancy discrepancy) {
        return new PaymentOutcome(Effect.NONE, discrepancy);
    }
}
