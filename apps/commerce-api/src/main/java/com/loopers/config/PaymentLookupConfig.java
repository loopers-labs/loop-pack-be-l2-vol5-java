package com.loopers.config;

import com.loopers.application.payment.PaymentLookupPolicy;
import com.loopers.application.payment.port.LookupJitter;
import com.loopers.domain.payment.LookupBackoff;
import com.loopers.infrastructure.payment.FullLookupJitter;
import com.loopers.infrastructure.payment.PaymentCircuitBreakers;
import com.loopers.infrastructure.payment.PaymentLookupCircuitProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentLookupCircuitProperties.class)
public class PaymentLookupConfig {
    @Bean
    LookupJitter lookupJitter() {
        return new FullLookupJitter(ThreadLocalRandom.current());
    }

    @Bean
    PaymentLookupPolicy paymentLookupPolicy(LookupJitter lookupJitter,
        @Value("${payment.lookup.initial-delay:5s}") Duration initialDelay,
        @Value("${payment.lookup.multiplier:2}") int multiplier,
        @Value("${payment.lookup.max-delay:1m}") Duration maxDelay,
        @Value("${payment.lookup.max-attempts:12}") int maxAttempts,
        @Value("${payment.lookup.batch-size:100}") int batchSize) {
        return new PaymentLookupPolicy(new LookupBackoff(initialDelay, multiplier, maxDelay),
            lookupJitter, maxAttempts, batchSize);
    }

    @Bean
    PaymentCircuitBreakers paymentCircuitBreakers(Clock clock, PaymentLookupCircuitProperties properties) {
        return new PaymentCircuitBreakers(clock, properties);
    }
}
