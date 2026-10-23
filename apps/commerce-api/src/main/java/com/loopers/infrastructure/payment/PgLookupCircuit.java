package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentGatewayResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Clock;
import java.time.Duration;

/** One in-process circuit shared by transaction-key and provider-order lookups for this local PG/account. */
public final class PgLookupCircuit {
    private PgLookupCircuit() {
    }

    public static CircuitBreaker create() {
        return CircuitBreaker.of("local-test-pg-lookup-135135", configuration(Clock.systemUTC()));
    }

    static CircuitBreakerConfig configuration(Clock clock) {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .slowCallRateThreshold(50)
            .slowCallDurationThreshold(Duration.ofSeconds(3))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(2)
            .maxWaitDurationInHalfOpenState(Duration.ofSeconds(40))
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordResult(value -> value instanceof PaymentGatewayResult result
                && result.kind() == PaymentGatewayResult.Kind.UNKNOWN)
            .clock(clock)
            .build();
    }
}
