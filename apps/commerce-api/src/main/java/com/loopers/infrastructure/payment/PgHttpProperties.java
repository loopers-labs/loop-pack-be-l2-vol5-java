package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * PG 연동 설정(commerce.pg). 기동 때 한 번 바인딩하는 불변 값이며 잘못된 값이면 컨텍스트 기동을 실패시킨다.
 * base/callback URL 은 로컬 설정으로만 정하고 고객 입력으로 바꾸지 않는다. callbackUrl 을 기동 후 알아낸 포트로
 * 늦게 교체하지 않으므로, 콜백 HTTP 테스트는 기동 전에 고른 포트를 server.port 와 함께 주입한다(설계 5.7).
 */
@ConfigurationProperties("commerce.pg")
public record PgHttpProperties(
    URI baseUrl,
    URI callbackUrl,
    String accountId,
    int maxConnections,
    Duration poolAcquireTimeout,
    Duration connectTimeout,
    Duration readTimeout
) {

    public PgHttpProperties {
        requireHttpUrl("base-url", baseUrl);
        requireHttpUrl("callback-url", callbackUrl);
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("commerce.pg.account-id 필수");
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("commerce.pg.max-connections 는 양수");
        }
        requirePositive("pool-acquire-timeout", poolAcquireTimeout);
        requirePositive("connect-timeout", connectTimeout);
        requirePositive("read-timeout", readTimeout);
    }

    private static void requireHttpUrl(String name, URI url) {
        if (url == null || !url.isAbsolute() || url.getHost() == null
            || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) {
            throw new IllegalArgumentException("commerce.pg." + name + " 는 http(s) 절대 URL");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("commerce.pg." + name + " 는 양의 시간");
        }
    }
}
