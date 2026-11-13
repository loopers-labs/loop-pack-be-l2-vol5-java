package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 쿠폰 캐시 시작 설정. 로컬 품절 TTL 은 단조 시계 나노초로 쓰므로 나노초 변환도 넘치지 않아야 한다.
 * 기본값(30초·30초·5초)은 관찰용 초기값이며 최적 TTL 의 실측이 아니다.
 */
@ConfigurationProperties("commerce.coupon.cache")
public record CouponCacheProperties(
    @DefaultValue("30s") Duration issuedTtl,
    @DefaultValue("30s") Duration soldOutTtl,
    @DefaultValue("5s") Duration localSoldOutTtl
) {

    private static final String PREFIX = "commerce.coupon.cache.";

    public CouponCacheProperties {
        requirePositiveMillis(issuedTtl, "issued-ttl");
        requirePositiveMillis(soldOutTtl, "sold-out-ttl");
        requirePositiveMillis(localSoldOutTtl, "local-sold-out-ttl");
        try {
            localSoldOutTtl.toNanos();
        } catch (ArithmeticException e) {
            throw invalid("local-sold-out-ttl", "나노초로 표현 가능한 기간", localSoldOutTtl.toString());
        }
    }

    private static void requirePositiveMillis(Duration value, String key) {
        CouponPropertyRules.requirePositiveMillis(PREFIX, key, value);
    }

    private static IllegalArgumentException invalid(String key, String rule, String value) {
        return CouponPropertyRules.invalid(PREFIX, key, rule, value);
    }
}
