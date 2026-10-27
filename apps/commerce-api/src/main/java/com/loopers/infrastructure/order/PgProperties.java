package com.loopers.infrastructure.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml pg.*. PG 연결 설정 (base-url, callback-url, 타임아웃).
 * read-timeout 은 결제 요청, lookup-read-timeout 은 거래 조회의 read timeout. pg.lookup-delay 는 application 이 읽는다 (PaymentLookupScheduler).
 */
@ConfigurationProperties("pg")
public record PgProperties(String baseUrl, String callbackUrl, Duration connectTimeout, Duration readTimeout, Duration lookupReadTimeout) {
}
