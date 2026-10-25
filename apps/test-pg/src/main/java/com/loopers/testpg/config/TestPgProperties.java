package com.loopers.testpg.config;

import com.loopers.testpg.application.ProcessingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("test-pg")
public record TestPgProperties(String providerAccountId, Duration processingDelay,
                               Duration callbackConnectTimeout, Duration callbackReadTimeout,
                               @DefaultValue("AUTO") ProcessingMode processingMode,
                               @DefaultValue("3s") Duration dropResponseDelay) {
    public TestPgProperties {
        if (providerAccountId == null || providerAccountId.isBlank()
            || processingDelay == null || processingDelay.isNegative()
            || callbackConnectTimeout == null || callbackConnectTimeout.isNegative() || callbackConnectTimeout.isZero()
            || callbackReadTimeout == null || callbackReadTimeout.isNegative() || callbackReadTimeout.isZero()
            || processingMode == null || dropResponseDelay == null || dropResponseDelay.isNegative()) {
            throw new IllegalArgumentException("테스트 PG 설정이 올바르지 않습니다.");
        }
    }
}
