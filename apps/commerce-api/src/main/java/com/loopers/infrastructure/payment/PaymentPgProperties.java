package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * 로컬 테스트 PG 연결 설정. 주소는 설정으로 고정하며 고객 요청이 바꿀 수 없다.
 *
 * <p>이 실습의 PG 는 로컬 계약이라 {@code 127.0.0.1} 밖의 주소는 시작할 때 거절한다.
 */
@ConfigurationProperties(prefix = "payment.pg")
public record PaymentPgProperties(
    String baseUrl,
    String callbackUrl,
    @DefaultValue("135135") String userId,
    @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("3s") Duration createReadTimeout,
    @DefaultValue("1s") Duration lookupReadTimeout
) {

    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost");

    public PaymentPgProperties {
        requireLocal("payment.pg.base-url", baseUrl);
        requireLocal("payment.pg.callback-url", callbackUrl);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("payment.pg.user-id 는 비어 있을 수 없습니다.");
        }
        requirePositive("payment.pg.connect-timeout", connectTimeout);
        requirePositive("payment.pg.create-read-timeout", createReadTimeout);
        requirePositive("payment.pg.lookup-read-timeout", lookupReadTimeout);
    }

    private static void requireLocal(String name, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(name + " 는 필수입니다.");
        }
        String host = URI.create(url).getHost();
        if (host == null || !LOCAL_HOSTS.contains(host)) {
            throw new IllegalArgumentException(name + " 는 로컬 주소(127.0.0.1)여야 합니다: " + url);
        }
    }

    private static void requirePositive(String name, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " 는 0보다 커야 합니다.");
        }
    }
}
