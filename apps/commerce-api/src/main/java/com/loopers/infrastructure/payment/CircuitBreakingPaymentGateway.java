package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentLookupNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.Objects;
import java.util.function.Supplier;

/** Wrap only the external GET, never create or the following database result application. */
public final class CircuitBreakingPaymentGateway implements PaymentGateway, AutoCloseable {
    private final PaymentGateway delegate;
    private final CircuitBreaker circuit;

    public CircuitBreakingPaymentGateway(PaymentGateway delegate, CircuitBreaker circuit) {
        this.delegate = Objects.requireNonNull(delegate);
        this.circuit = Objects.requireNonNull(circuit);
    }

    @Override
    public PaymentGatewayResult create(PaymentCreateRequest request) {
        return delegate.create(request);
    }

    @Override
    public PaymentGatewayResult findByTransactionKey(String transactionKey) {
        return lookup(() -> delegate.findByTransactionKey(transactionKey));
    }

    @Override
    public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
        return lookup(() -> delegate.findByProviderOrderId(providerOrderId));
    }

    private PaymentGatewayResult lookup(Supplier<PaymentGatewayResult> operation) {
        if (!circuit.tryAcquirePermission()) {
            throw new PaymentLookupNotPermittedException();
        }
        long started = circuit.getCurrentTimestamp();
        PaymentGatewayResult result;
        try {
            result = operation.get();
        } catch (RuntimeException failure) {
            circuit.onError(circuit.getCurrentTimestamp() - started, circuit.getTimestampUnit(), failure);
            throw failure;
        }
        circuit.onResult(circuit.getCurrentTimestamp() - started, circuit.getTimestampUnit(), result);
        return result;
    }

    @Override
    public void close() throws Exception {
        circuit.reset();
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
