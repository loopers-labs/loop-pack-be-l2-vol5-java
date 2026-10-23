package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("payment.pg.lookup-policy")
public record PgLookupPolicyProperties(@DefaultValue RetrySettings retry,
                                       @DefaultValue RateSettings rateLimiter,
                                       @DefaultValue TimeSettings timeLimiter) {
    public record RetrySettings(@DefaultValue("false") boolean enabled,
                                @DefaultValue("3") int maxAttempts,
                                @DefaultValue("100ms") Duration initialWait,
                                @DefaultValue("2") double multiplier,
                                @DefaultValue("0.2") double jitter) {}
    public record RateSettings(@DefaultValue("false") boolean enabled,
                               @DefaultValue("20") int limitForPeriod,
                               @DefaultValue("1s") Duration refreshPeriod) {}
    public record TimeSettings(@DefaultValue("false") boolean enabled,
                               @DefaultValue("900ms") Duration timeout) {}
}
