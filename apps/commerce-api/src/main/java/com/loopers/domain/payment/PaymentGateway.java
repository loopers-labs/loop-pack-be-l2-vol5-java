package com.loopers.domain.payment;

/** External observations only. This port does not commit an order or change stock. */
public interface PaymentGateway {
    PaymentGatewayResult create(PaymentCreateRequest request);

    PaymentGatewayResult findByTransactionKey(String transactionKey);

    PaymentGatewayResult findByProviderOrderId(String providerOrderId);
}
