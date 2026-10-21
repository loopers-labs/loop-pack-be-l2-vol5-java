package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 발송 풀 (ADR-W5-01·03, plan.md 0-1). 동시 PG 대기 = 100 TPS × 0.5s = 50.
 * 대기열은 0이다 — 줄 세워 늦게 보내지 않고, 넘치면 바로 실패로 닫는다.
 */
@ConfigurationProperties(prefix = "payment.dispatch")
public record PaymentDispatchProperties(int poolSize, int queueCapacity) {

    public PaymentDispatchProperties {
        if (poolSize <= 0) {
            poolSize = 50;
        }
        if (queueCapacity < 0) {
            queueCapacity = 0;
        }
    }
}
