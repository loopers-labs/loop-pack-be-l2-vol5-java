package com.loopers.domain.payment;

/** The lookup circuit rejected this call before HTTP; this is not a PG failure or a consumed HTTP attempt. */
public class PaymentLookupNotPermittedException extends RuntimeException {
    public PaymentLookupNotPermittedException() {
        super("Payment lookup is temporarily not permitted");
    }
}
