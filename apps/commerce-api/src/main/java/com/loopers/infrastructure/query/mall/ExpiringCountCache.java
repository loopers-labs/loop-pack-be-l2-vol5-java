package com.loopers.infrastructure.query.mall;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

// TTL 동안 개수 계산 결과를 재사용하는 단일 값 캐시
public class ExpiringCountCache {
    private final Clock clock;
    private final Duration ttl;
    private volatile Entry entry;

    public ExpiringCountCache(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    // 캐시가 유효하면 저장된 값을, 아니면 계산한 값을 저장하고 돌려준다
    public long get(LongSupplier computer) {
        Instant now = clock.instant();
        Entry current = entry;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.value();
        }
        long value = computer.getAsLong();
        entry = new Entry(value, now.plus(ttl));
        return value;
    }

    private record Entry(long value, Instant expiresAt) {}
}
