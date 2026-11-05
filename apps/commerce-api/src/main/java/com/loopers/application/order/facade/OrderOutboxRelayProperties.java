package com.loopers.application.order.facade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 값의 근거는 relay 간격 실험 문서 (7주차 D-108) */
@ConfigurationProperties("order-outbox.relay")
public record OrderOutboxRelayProperties(
    int batchSize,
    Duration lease,
    Duration backoffInitial,
    Duration backoffMax
) {}
