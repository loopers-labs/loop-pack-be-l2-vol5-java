package com.loopers.domains.payment.config;

import com.loopers.domains.payment.app.PaymentExpiryPolicy;
import com.loopers.domains.payment.app.PaymentLookupPolicy;
import com.loopers.domains.payment.app.port.LookupJitter;
import com.loopers.domains.payment.domain.LookupBackoff;
import com.loopers.domains.payment.out.FullLookupJitter;
import com.loopers.domains.payment.out.PaymentCircuitBreakers;
import com.loopers.domains.payment.out.PaymentLookupCircuitProperties;
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
        @Value("${payment.lookup.initial-delay:2s}") Duration initialDelay,
        @Value("${payment.lookup.multiplier:2}") int multiplier,
        @Value("${payment.lookup.max-delay:20s}") Duration maxDelay,
        @Value("${payment.lookup.max-attempts:12}") int maxAttempts,
        @Value("${payment.lookup.batch-size:100}") int batchSize) {
        return new PaymentLookupPolicy(new LookupBackoff(initialDelay, multiplier, maxDelay),
            lookupJitter, maxAttempts, batchSize);
    }

    @Bean
    PaymentExpiryPolicy paymentExpiryPolicy(@Value("${payment.expiry.timeout:2m}") Duration timeout) {
        return new PaymentExpiryPolicy(timeout);
    }

    @Bean
    PaymentCircuitBreakers paymentCircuitBreakers(Clock clock, PaymentLookupCircuitProperties properties) {
        return new PaymentCircuitBreakers(clock, properties);
    }
}
