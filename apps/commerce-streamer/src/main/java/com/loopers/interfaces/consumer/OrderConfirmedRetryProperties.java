package com.loopers.interfaces.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 메시지 문제가 아닌 소비 실패(DB 등)의 재시도 간격. maxElapsedTime은 기다린 간격의 합이다 (ADR 0053). */
@ConfigurationProperties(prefix = "commerce.order-events.consumer.retry")
public record OrderConfirmedRetryProperties(
    @DefaultValue("1s") Duration initialInterval,
    @DefaultValue("2") double multiplier,
    @DefaultValue("30s") Duration maxInterval,
    @DefaultValue("5m") Duration maxElapsedTime
) {}
