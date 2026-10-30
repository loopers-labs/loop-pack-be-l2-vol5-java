package com.loopers.payment.adapter.out.pg;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 로컬 테스트 PG 연결 설정 (plan.md 6·7장). base URL·callback URL은 로컬 설정에서만 온다.
 * 생성과 조회는 읽기 timeout을 따로 둔다 — 생성은 길게 기다리고 재시도하지 않으며, 조회는 짧게 끊고 재시도한다.
 */
@ConfigurationProperties(prefix = "pg.local")
public record PgProperties(
    String baseUrl,
    String userId,
    String callbackUrl,
    Duration connectTimeout,
    Duration createReadTimeout,
    Duration lookupReadTimeout
) {

    private static final String DEFAULT_USER_ID = "135135";

    public PgProperties {
        requireText(baseUrl, "pg.local.base-url");
        requireText(callbackUrl, "pg.local.callback-url");
        if (userId == null || userId.isBlank()) {
            userId = DEFAULT_USER_ID;
        }
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(1), "pg.local.connect-timeout");
        createReadTimeout = positiveOrDefault(createReadTimeout, Duration.ofSeconds(3), "pg.local.create-read-timeout");
        lookupReadTimeout = positiveOrDefault(lookupReadTimeout, Duration.ofSeconds(1), "pg.local.lookup-read-timeout");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 필요합니다");
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "는 0보다 커야 합니다: " + value);
        }
        return value;
    }
}
