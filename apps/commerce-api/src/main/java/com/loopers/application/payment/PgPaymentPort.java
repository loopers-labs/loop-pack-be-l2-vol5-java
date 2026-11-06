package com.loopers.application.payment;

import java.util.List;

public interface PgPaymentPort {

    PgPaymentResult pay(PgPaymentRequest request);

    PgPaymentResult getByTransactionKey(String transactionKey);

    List<PgPaymentResult> getByProviderOrderId(String providerOrderId);
}
