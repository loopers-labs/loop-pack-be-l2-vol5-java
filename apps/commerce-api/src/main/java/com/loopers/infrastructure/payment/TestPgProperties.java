package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("commerce.test-pg")
public record TestPgProperties(
    @DefaultValue("http://127.0.0.1:9090") URI baseUrl,
    @DefaultValue("http://127.0.0.1:8080/api/v1/payments/callback") URI callbackUrl,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("1s") Duration poolTimeout,
    @DefaultValue("60s") Duration createResponseTimeout,
    @DefaultValue("5s") Duration lookupResponseTimeout,
    @DefaultValue("65s") Duration createTotalTimeout,
    @DefaultValue("10s") Duration lookupTotalTimeout
) {
    public PgHttpTimeouts timeouts() {
        return new PgHttpTimeouts(connectTimeout, poolTimeout, createResponseTimeout, lookupResponseTimeout,
            createTotalTimeout, lookupTotalTimeout);
    }
}
