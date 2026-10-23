package com.loopers.infrastructure.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 조회 경로의 재조회 · 회로 설정 (5주차 설계 5장). 강의 예시를 따른 초기값 */
@ConfigurationProperties("payment.test-pg.lookup")
public record TestPgLookupPolicyProperties(
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("100ms") Duration retryWait,
    @DefaultValue("2") double retryWaitMultiplier,
    @DefaultValue("10") int slidingWindowSize,
    @DefaultValue("5") int minimumNumberOfCalls,
    @DefaultValue("50") float failureRateThreshold,
    @DefaultValue("10s") Duration openWait,
    @DefaultValue("2") int halfOpenCalls
) {}
