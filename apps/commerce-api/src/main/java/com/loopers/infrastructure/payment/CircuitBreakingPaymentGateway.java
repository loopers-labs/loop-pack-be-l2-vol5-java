package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentGateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

@Component
public class CircuitBreakingPaymentGateway implements PaymentGateway {
    private final HttpPaymentGateway delegate;
    private final CircuitBreaker lookupCircuit;

    public CircuitBreakingPaymentGateway(HttpPaymentGateway delegate, CircuitBreaker paymentLookupCircuitBreaker) {
        this.delegate = delegate;
        this.lookupCircuit = paymentLookupCircuitBreaker;
    }

    @Override
    public CreatedPayment create(String providerOrderId, long amount, String cardType, String cardNo) {
        return delegate.create(providerOrderId, amount, cardType, cardNo);
    }

    @Override
    public List<ObservedPayment> findByOrderId(String providerOrderId) {
        return executeLookup(() -> delegate.findByOrderId(providerOrderId));
    }

    List<ObservedPayment> findByOrderId(String providerOrderId, Duration remainingBudget) {
        return executeLookup(() -> delegate.findByOrderId(providerOrderId, remainingBudget));
    }

    @Override
    public ObservedPayment findByTransactionKey(String transactionKey) {
        return executeLookup(() -> delegate.findByTransactionKey(transactionKey));
    }

    ObservedPayment findByTransactionKey(String transactionKey, Duration remainingBudget) {
        return executeLookup(() -> delegate.findByTransactionKey(transactionKey, remainingBudget));
    }

    private <T> T executeLookup(Supplier<T> lookup) {
        try {
            return lookupCircuit.executeSupplier(lookup);
        } catch (CallNotPermittedException exception) {
            throw new LookupBlockedException(exception);
        }
    }
}
