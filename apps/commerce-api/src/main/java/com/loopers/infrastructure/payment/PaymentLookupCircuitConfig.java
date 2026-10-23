package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentGateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.CancellationException;

@Configuration(proxyBeanMethods = false)
public class PaymentLookupCircuitConfig {
    @Bean
    CircuitBreaker paymentLookupCircuitBreaker(
            @Value("${payment-provider.lookup-circuit.sliding-window-size}") int windowSize,
            @Value("${payment-provider.lookup-circuit.minimum-number-of-calls}") int minimumCalls,
            @Value("${payment-provider.lookup-circuit.failure-rate-threshold}") float failureRate,
            @Value("${payment-provider.lookup-circuit.wait-duration-in-open-state}") Duration openDuration,
            @Value("${payment-provider.lookup-circuit.permitted-calls-in-half-open-state}") int probeCalls) {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(probeCalls)
                .ignoreException(error -> !isAvailabilityFailure(error))
                .build();
        return CircuitBreaker.of("pg-payment-lookup", config);
    }

    private static boolean isAvailabilityFailure(Throwable error) {
        Throwable cause = error.getCause();
        return error instanceof PaymentGateway.UnconfirmedException
                && (cause instanceof ResourceAccessException || cause instanceof HttpServerErrorException
                        || cause instanceof CancellationException);
    }
}
