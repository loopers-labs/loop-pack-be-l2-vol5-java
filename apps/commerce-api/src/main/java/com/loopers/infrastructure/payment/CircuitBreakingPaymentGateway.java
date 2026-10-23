package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentCreateCommand;
import com.loopers.application.payment.PaymentCreateObservation;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentLookupObservation;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Primary
@Component
@ConditionalOnProperty(prefix = "payment.pg.lookup-circuit-breaker", name = "enabled", havingValue = "true")
public class CircuitBreakingPaymentGateway implements PaymentGateway {
    private final PaymentGateway delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakingPaymentGateway(@Qualifier("pgLookupDelegate") PaymentGateway delegate,
                                         @Qualifier("pgLookupCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public PaymentCreateObservation create(PaymentCreateCommand command) {
        return delegate.create(command);
    }

    @Override
    public PaymentLookupObservation lookup(String transactionKey) {
        return executeLookup(() -> delegate.lookup(transactionKey));
    }

    @Override
    public PaymentLookupObservation lookupByOrder(String providerOrderId) {
        return executeLookup(() -> delegate.lookupByOrder(providerOrderId));
    }

    private PaymentLookupObservation executeLookup(Supplier<PaymentLookupObservation> lookup) {
        try {
            return circuitBreaker.executeSupplier(lookup);
        } catch (PgLookupPolicyException exception) {
            return exception.observation();
        } catch (CallNotPermittedException exception) {
            return new PaymentLookupObservation.Unavailable("CIRCUIT_OPEN", PaymentLookupObservation.FailureKind.CIRCUIT_OPEN);
        }
    }
}
