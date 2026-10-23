package com.loopers.batch.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class PaymentLookupCircuitBreakerConfiguration {

    @Bean
    public CircuitBreaker paymentLookupCircuitBreaker(
        @Value("${payment.resilience.circuit-breaker.sliding-window-seconds:30}") int slidingWindowSeconds,
        @Value("${payment.resilience.circuit-breaker.minimum-number-of-calls:20}") int minimumNumberOfCalls,
        @Value("${payment.resilience.circuit-breaker.failure-rate-threshold:70}") float failureRateThreshold,
        @Value("${payment.resilience.circuit-breaker.wait-duration-in-open-state:30s}") Duration waitDuration,
        @Value("${payment.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state:2}")
        int halfOpenCalls
    ) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(slidingWindowSeconds)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(waitDuration)
            .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
            .recordResult(result -> result instanceof PaymentLookupClient.LookupResult lookup
                && (lookup.httpStatus() == 0 || lookup.httpStatus() >= 500))
            .build();
        return CircuitBreaker.of("toss-payment-batch-lookup", config);
    }
}
