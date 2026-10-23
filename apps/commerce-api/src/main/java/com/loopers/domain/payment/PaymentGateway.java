package com.loopers.domain.payment;

public interface PaymentGateway {

    CreateObservation create(PaymentCreation creation);

    LookupResult lookup(TransactionKey transactionKey);

    LookupResult lookup(ProviderOrderId providerOrderId);

    LookupResult lookupRetrying(TransactionKey transactionKey);

    LookupResult lookupRetrying(ProviderOrderId providerOrderId);
}
