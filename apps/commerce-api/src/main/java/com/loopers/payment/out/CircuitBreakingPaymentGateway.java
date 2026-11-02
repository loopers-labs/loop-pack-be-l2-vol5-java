package com.loopers.payment.out;

import com.loopers.payment.app.port.PaymentCreateCommand;
import com.loopers.payment.app.port.PaymentCreateResult;
import com.loopers.payment.app.port.PaymentGateway;
import com.loopers.payment.app.port.PaymentTransactionsLookup;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public class CircuitBreakingPaymentGateway implements PaymentGateway {
    private final PaymentGateway paymentGateway;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakingPaymentGateway(PaymentGateway paymentGateway, CircuitBreaker circuitBreaker) {
        this.paymentGateway = paymentGateway;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String providerId() {
        return paymentGateway.providerId();
    }

    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        return paymentGateway.create(command);
    }

    @Override
    public PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey) {
        try {
            return circuitBreaker.executeSupplier(() -> paymentGateway.verifyOrder(providerOrderId, storedTransactionKey));
        } catch (CallNotPermittedException e) {
            return new PaymentTransactionsLookup.NotCalled();
        }
    }
}
