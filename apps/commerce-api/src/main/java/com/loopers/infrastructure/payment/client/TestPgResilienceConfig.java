package com.loopers.infrastructure.payment.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 회로 상태를 테스트에서 reset 할 수 있도록 bean 으로 둠 (D-64) */
@Configuration
public class TestPgResilienceConfig {

    private static final String LOOKUP = "testPgLookup";

    @Bean
    public CircuitBreaker testPgLookupCircuitBreaker(TestPgLookupPolicyProperties properties) {
        return lookupCircuitBreaker(properties);
    }

    @Bean
    public Retry testPgLookupRetry(TestPgLookupPolicyProperties properties) {
        return lookupRetry(properties);
    }

    public static CircuitBreaker lookupCircuitBreaker(TestPgLookupPolicyProperties properties) {
        return CircuitBreaker.of(LOOKUP, CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(properties.openWait())
            .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
            .recordExceptions(LookupFailure.class)
            .build());
    }

    /** OPEN 의 CallNotPermittedException 은 재조회하지 않음 */
    public static Retry lookupRetry(TestPgLookupPolicyProperties properties) {
        return Retry.of(LOOKUP, RetryConfig.custom()
            .maxAttempts(properties.maxAttempts())
            .intervalFunction(IntervalFunction.ofExponentialBackoff(properties.retryWait(), properties.retryWaitMultiplier()))
            .retryOnException(e -> e instanceof LookupFailure failure && failure.isRetryable())
            .build());
    }
}
