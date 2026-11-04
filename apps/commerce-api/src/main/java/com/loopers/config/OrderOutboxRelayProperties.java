package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbox relay 설정(commerce.order-events.relay, 설계 W7.3-1). 값은 사용자가 고른 초기값이며 실측 적정값·SLA 가 아니다.
 * 잘못된 범위·관계는 기동 오류다. enabled·fixedDelay 는 자동 scheduler 용이며 현재 단위에서는 scheduler 를 조립하지 않는다.
 */
@ConfigurationProperties("commerce.order-events.relay")
public record OrderOutboxRelayProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("1s") Duration fixedDelay,
    @DefaultValue("10") int batchSize,
    @DefaultValue("35s") Duration sendWaitTimeout,
    @DefaultValue("5s") Duration initialRetryDelay,
    @DefaultValue("60s") Duration maxRetryDelay,
    @DefaultValue("10") int maxCycleAttempts
) {

    public OrderOutboxRelayProperties {
        requirePositive(fixedDelay, "fixed-delay");
        requirePositive(sendWaitTimeout, "send-wait-timeout");
        requirePositive(initialRetryDelay, "initial-retry-delay");
        requirePositive(maxRetryDelay, "max-retry-delay");
        if (batchSize < 1) {
            throw invalid("batch-size", "1 이상");
        }
        if (maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw invalid("max-retry-delay", "initial-retry-delay 이상");
        }
        if (maxCycleAttempts < 1) {
            throw invalid("max-cycle-attempts", "1 이상");
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw invalid(key, "양수");
        }
    }

    private static IllegalArgumentException invalid(String key, String rule) {
        return new IllegalArgumentException("commerce.order-events.relay." + key + " 는 " + rule + "여야 합니다.");
    }
}
