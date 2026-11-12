package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coupon.queue")
public record CouponQueueProperties(
    int maxQueueSize,
    int capacity,
    long heartbeatTimeoutSeconds,
    long admitTtlSeconds,
    long promoteDelayMs
) {
    public CouponQueueProperties {
        if (maxQueueSize <= 0) maxQueueSize = 1000;
        if (capacity <= 0) capacity = 50;
        if (heartbeatTimeoutSeconds <= 0) heartbeatTimeoutSeconds = 30;
        if (admitTtlSeconds <= 0) admitTtlSeconds = 30;
        if (promoteDelayMs <= 0) promoteDelayMs = 200;
    }
}
