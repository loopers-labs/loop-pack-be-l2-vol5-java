package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 쿠폰 기발급 결과·품절 Redis 캐시 시작 설정(commerce.coupon.cache, 설계 §13.9). 변경은 재시작으로 적용한다.
 * 두 TTL 은 Redis 에 밀리초로 넘기므로 1ms 이상이면서 밀리초 변환이 넘치지 않아야 한다. 어긋나면 기동 오류다.
 * 기본값 30초는 관찰용 초기값이며 최적 TTL 의 실측이 아니다.
 */
@ConfigurationProperties("commerce.coupon.cache")
public record CouponCacheProperties(
    @DefaultValue("30s") Duration issuedTtl,
    @DefaultValue("30s") Duration soldOutTtl
) {

    public CouponCacheProperties {
        requirePositiveMillis(issuedTtl, "issued-ttl");
        requirePositiveMillis(soldOutTtl, "sold-out-ttl");
    }

    private static void requirePositiveMillis(Duration value, String key) {
        if (value == null) {
            throw invalid(key, "1ms 이상의 기간", "null");
        }
        long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException e) {
            throw invalid(key, "밀리초로 표현 가능한 기간", value.toString());
        }
        if (millis < 1) {
            throw invalid(key, "1ms 이상의 기간", value.toString());
        }
    }

    private static IllegalArgumentException invalid(String key, String rule, String value) {
        return new IllegalArgumentException("commerce.coupon.cache." + key + " 는 " + rule + "여야 합니다. [" + key + " = " + value + "]");
    }
}
