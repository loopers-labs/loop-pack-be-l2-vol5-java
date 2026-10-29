package com.loopers.infrastructure.payment;

import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;

public class PaymentCircuitBreakers {
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentCircuitBreakers(Clock clock, PaymentLookupCircuitProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(properties.openDuration())
            .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
            // 자동 전환은 별도 실제 시간 스레드를 사용하므로 주입 Clock으로 호출 시 전환하게 한다.
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .clock(clock)
            .recordResult(result -> result instanceof PaymentTransactionsLookup.Failed)
            .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(config);
    }

    public PaymentGateway wrap(PaymentGateway gateway) {
        return new CircuitBreakingPaymentGateway(gateway, circuitBreakerRegistry.circuitBreaker(gateway.providerId()));
    }
}
