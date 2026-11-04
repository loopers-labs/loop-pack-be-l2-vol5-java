package com.loopers.application.order.facade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 측정 근거 없는 기본값으로 시작해 실험으로 다시 정함 (7주차 D-108) */
@ConfigurationProperties("order-outbox.relay")
public record OrderOutboxRelayProperties(
    int batchSize,
    Duration lease,
    Duration backoffInitial,
    Duration backoffMax
) {}
