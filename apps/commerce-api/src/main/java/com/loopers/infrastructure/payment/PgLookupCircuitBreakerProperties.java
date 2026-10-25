package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("payment.pg.lookup-circuit-breaker")
public record PgLookupCircuitBreakerProperties(int slidingWindowSize, int minimumNumberOfCalls,
                                                float failureRateThreshold, Duration waitDurationInOpenState,
                                                int permittedNumberOfCallsInHalfOpenState) {}
