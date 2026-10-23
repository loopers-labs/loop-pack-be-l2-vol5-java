package com.loopers.infrastructure.payment;

import java.time.Duration;
import java.util.List;

public record PgHttpTimeouts(Duration connect, Duration pool, Duration createResponse, Duration lookupResponse,
                             Duration createTotal, Duration lookupTotal) {
    public PgHttpTimeouts {
        for (Duration value : List.of(connect, pool, createResponse, lookupResponse, createTotal, lookupTotal)) {
            if (value.toMillis() < 1) {
                throw new IllegalArgumentException("PG timeouts must be at least one millisecond");
            }
        }
    }

    public static PgHttpTimeouts defaults() {
        return new PgHttpTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(60),
            Duration.ofSeconds(5), Duration.ofSeconds(65), Duration.ofSeconds(10));
    }
}
