package com.loopers.domain.payment;

public interface PaymentProvider {
    PaymentObservation create(PaymentRequest request);
    PaymentObservation findByTransactionKey(String transactionKey);
    PaymentObservation findByOrderId(String providerOrderId);
}
