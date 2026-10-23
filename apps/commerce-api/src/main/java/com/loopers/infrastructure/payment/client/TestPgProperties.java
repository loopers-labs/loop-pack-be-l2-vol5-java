package com.loopers.infrastructure.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** base URL · callback URL 은 고객 입력으로 바꾸지 않도록 설정으로 고정함 */
@ConfigurationProperties("payment.test-pg")
public record TestPgProperties(
    String baseUrl,
    String callbackUrl,
    @DefaultValue("135135") String providerUserId,
    @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("3s") Duration createReadTimeout,
    @DefaultValue("1s") Duration lookupReadTimeout
) {}
