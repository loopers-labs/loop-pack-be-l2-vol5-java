package com.loopers.application.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Outbox relay 설정 (7주차 설계 D22·D28). 값은 application.yml 의 outbox.relay 에서 바인딩된다.
 * enabled 가 false 여도 스케줄러 빈은 만들어지고 @Scheduled 자동 실행만 꺼진다. 테스트는 relay 의 runOnce() 를 직접 호출한다.
 * 운영 시작값(대기 3초·묶음 20·간격 10초·backoff 10초×2 상한 5분)은 측정 없이 정한 값이다.
 */
@ConfigurationProperties(value = "outbox.relay")
public record OutboxRelayProperties(
    boolean enabled,
    Duration fixedDelay,
    int batchSize,
    Duration sendTimeout,
    Duration initialBackoff,
    int multiplier,
    Duration maxBackoff
) { }
