package com.loopers.infrastructure.query.mall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpiringCountCacheTest {
    private final MutableClock clock = new MutableClock();
    private final AtomicLong computeCalls = new AtomicLong();

    private long compute() {
        return 100L + computeCalls.incrementAndGet();
    }

    @DisplayName("TTL 안에서는 다시 계산하지 않고 캐시된 값을 돌려준다")
    @Test
    void reusesValueWithinTtl() {
        ExpiringCountCache cache = new ExpiringCountCache(clock, Duration.ofSeconds(30));

        long first = cache.get(this::compute);
        clock.advance(Duration.ofSeconds(29));
        long second = cache.get(this::compute);

        assertThat(first).isEqualTo(101L);
        assertThat(second).isEqualTo(101L);
        assertThat(computeCalls).hasValue(1);
    }

    @DisplayName("TTL이 지나면 다시 계산한다")
    @Test
    void recomputesAfterTtl() {
        ExpiringCountCache cache = new ExpiringCountCache(clock, Duration.ofSeconds(30));

        cache.get(this::compute);
        clock.advance(Duration.ofSeconds(30));
        long refreshed = cache.get(this::compute);

        assertThat(refreshed).isEqualTo(102L);
        assertThat(computeCalls).hasValue(2);
    }

    @DisplayName("TTL이 0이면 매번 계산한다")
    @Test
    void alwaysRecomputesWhenTtlIsZero() {
        ExpiringCountCache cache = new ExpiringCountCache(clock, Duration.ZERO);

        cache.get(this::compute);
        cache.get(this::compute);
        cache.get(this::compute);

        assertThat(computeCalls).hasValue(3);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
