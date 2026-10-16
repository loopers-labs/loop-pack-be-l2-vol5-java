package com.loopers.infrastructure.query.mall;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("query.product-detail-cache")
public record ProductDetailCacheProperties(@DefaultValue("false") boolean enabled,
                                           @DefaultValue("1000") long maximumSize,
                                           @DefaultValue("30s") Duration ttl) {
    public ProductDetailCacheProperties {
        if (enabled && maximumSize <= 0) {
            throw new IllegalArgumentException("상세 캐시의 최대 크기는 양수여야 합니다.");
        }
        if (enabled && (ttl == null || ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("상세 캐시의 TTL은 양수여야 합니다.");
        }
    }
}
