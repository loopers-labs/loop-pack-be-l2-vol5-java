package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// base URL·callback URL·제공자 계정은 설정으로 고정하며 고객 입력으로 바꿀 수 없다.
@ConfigurationProperties("payment.pg")
public record PgProperties(String baseUrl, String callbackUrl, String providerAccountId,
                           Duration connectTimeout, Duration readTimeout) {}
