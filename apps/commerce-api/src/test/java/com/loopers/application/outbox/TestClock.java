package com.loopers.application.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 현재 시각에서 시작해 앞으로만 넘기는 시계(D-16). µs로 자른다 — DB 컬럼(datetime(6))과 같은 정밀도라 저장한 시각과
 * 계산한 시각을 그대로 비교할 수 있다. {@link #restartAtRealNow()}는 DB를 비운 직후 장면 시작에만 부른다.
 *
 * <p>relay 테스트(OutboxRelayTest)와 배달 E2E(OrderConfirmedOutboxDeliveryE2ETest)가 함께 쓴다.
 */
class TestClock extends Clock {

    private volatile Instant instant;

    TestClock() {
        restartAtRealNow();
    }

    void restartAtRealNow() {
        instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 확정 TX가 JVM 시각으로 적은 첫 시도 시각을 따라잡는다. 실제 시각이 앞서 있을 때만 움직인다. */
    void catchUpToRealNow() {
        Instant real = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (real.isAfter(instant)) {
            instant = real;
        }
    }

    void advance(Duration duration) {
        assertThat(duration.isNegative()).as("시계는 앞으로만 간다").isFalse();
        instant = instant.plus(duration);
    }

    void advanceTo(ZonedDateTime target) {
        Instant to = target.toInstant();
        assertThat(to).as("시계는 앞으로만 간다 (지금 %s)", instant).isAfterOrEqualTo(instant);
        instant = to;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
