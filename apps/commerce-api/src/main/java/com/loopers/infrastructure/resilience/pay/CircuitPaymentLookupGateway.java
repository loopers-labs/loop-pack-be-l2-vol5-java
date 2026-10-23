package com.loopers.infrastructure.resilience.pay;

import com.loopers.application.pay.gateway.PaymentLookupGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.policy.PaymentLookupPolicy;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CircuitPaymentLookupGateway implements PaymentLookupGateway {
    public static final String CIRCUIT_NAME = "pg-lookup";
    private final PaymentProviderGateway provider;

    @Override
    @CircuitBreaker(name = CIRCUIT_NAME, fallbackMethod = "unavailable")
    public PaymentObservation lookup(LookupRequest request) {
        PaymentObservation observation = provider.lookup(request.expected());
        if (PaymentLookupPolicy.circuit(observation, request.outcome(), request.rejection())
                == PaymentLookupPolicy.Circuit.EXCLUDED) {
            throw new IgnoredPaymentLookupException(observation);
        }
        return observation;
    }

    private PaymentObservation unavailable(LookupRequest request, IgnoredPaymentLookupException failure) {
        return failure.observation();
    }

    private PaymentObservation unavailable(LookupRequest request, CallNotPermittedException failure) {
        return PaymentObservation.unavailable(PaymentObservation.Reason.CIRCUIT_BLOCKED);
    }
}
