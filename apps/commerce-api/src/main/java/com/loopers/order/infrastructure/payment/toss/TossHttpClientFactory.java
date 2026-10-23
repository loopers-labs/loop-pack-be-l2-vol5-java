package com.loopers.order.infrastructure.payment.toss;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

public final class TossHttpClientFactory {

    private TossHttpClientFactory() {
    }

    public static RestClient create(
        URI baseUrl,
        String secretKey,
        Duration connectTimeout,
        Duration readTimeout
    ) {
        if (connectTimeout.isNegative() || connectTimeout.isZero()
            || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("HTTP timeout must be positive");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
            .baseUrl(baseUrl.toString())
            .requestFactory(factory)
            .defaultHeaders(headers -> headers.setBasicAuth(secretKey, ""))
            .build();
    }
}
