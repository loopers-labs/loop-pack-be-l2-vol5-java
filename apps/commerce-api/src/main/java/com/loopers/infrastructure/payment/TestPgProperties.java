package com.loopers.infrastructure.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.providers.test-pg")
public record TestPgProperties(URI baseUrl, String userId, String callbackUrl, Duration connectTimeout, Duration readTimeout) {
    public TestPgProperties {
        if (baseUrl == null || userId == null || userId.isBlank() || callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("테스트 PG의 base-url·user-id·callback-url 설정이 필요합니다.");
        }
        if (connectTimeout == null || readTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
            || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("테스트 PG의 connect-timeout·read-timeout은 양수여야 합니다.");
        }
    }
}
