package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pg")
public record PgProperties(String baseUrl, String providerId) {
}
