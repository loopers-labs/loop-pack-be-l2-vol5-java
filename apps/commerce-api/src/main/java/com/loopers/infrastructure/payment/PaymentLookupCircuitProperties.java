package com.loopers.infrastructure.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.lookup.circuit")
public record PaymentLookupCircuitProperties(@DefaultValue("20") int slidingWindowSize,
    @DefaultValue("10") int minimumNumberOfCalls, @DefaultValue("50") float failureRateThreshold,
    @DefaultValue("30s") Duration openDuration, @DefaultValue("3") int halfOpenCalls) {
}
