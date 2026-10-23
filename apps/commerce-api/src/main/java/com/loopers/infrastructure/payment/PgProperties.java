package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("pg")
record PgProperties(
    String baseUrl,
    String callbackUrl,
    String userId,
    String sslBundle,
    Duration connectTimeout,
    Duration createTimeout,
    Duration lookupTimeout,
    Duration poolWaitTimeout,
    int maxConnections,
    int createConcurrency,
    LookupCircuit lookupCircuit
) {

    record LookupCircuit(
        Duration window,
        int minimumCalls,
        float failureRatePercent,
        Duration slowCallDuration,
        float slowCallRatePercent,
        Duration openWait,
        int halfOpenCalls
    ) {
    }
}
