package com.loopers.infrastructure.product.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("commerce.product-list-cache")
public record ProductListCacheProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("commerce:local:products:v1") String namespace,
    @DefaultValue("60s") Duration ttl,
    @DefaultValue("100ms") Duration connectTimeout,
    @DefaultValue("100ms") Duration commandTimeout
) {
    public ProductListCacheProperties {
        if (namespace == null || namespace.isBlank() || namespace.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Cache namespace must be non-empty and contain no whitespace");
        }
        requirePositiveMillis(ttl, "ttl");
        requirePositiveMillis(connectTimeout, "connectTimeout");
        requirePositiveMillis(commandTimeout, "commandTimeout");
    }

    private static void requirePositiveMillis(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
    }
}
