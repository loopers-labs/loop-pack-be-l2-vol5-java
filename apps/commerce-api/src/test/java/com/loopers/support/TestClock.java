package com.loopers.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 실제로 기다리는 대신 시계가 보는 시간만 앞으로 옮긴다. */
public final class TestClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public TestClock() {
        this(new AtomicReference<>(Instant.EPOCH), ZoneOffset.UTC);
    }

    private TestClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
