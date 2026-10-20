package com.loopers.infrastructure.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.yml pg.*. PG 연결 설정 (base-url, callback-url, 타임아웃). */
@ConfigurationProperties("pg")
public record PgProperties(String baseUrl, String callbackUrl, Duration connectTimeout, Duration readTimeout) {
}
