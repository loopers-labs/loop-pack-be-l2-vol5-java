package com.loopers.domain.payment;

public class PaymentProviderUnavailableException extends RuntimeException {
    public PaymentProviderUnavailableException() {
        super("Payment provider is not enabled");
    }
}
