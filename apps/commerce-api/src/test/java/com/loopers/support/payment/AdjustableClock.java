package com.loopers.support.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class AdjustableClock extends Clock {
    private final AtomicReference<Instant> currentTime;
    private final ZoneId zone;

    public AdjustableClock() {
        this(new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z")), ZoneOffset.UTC);
    }

    private AdjustableClock(AtomicReference<Instant> currentTime, ZoneId zone) {
        this.currentTime = currentTime;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        currentTime.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(currentTime, zone);
    }

    @Override
    public Instant instant() {
        return currentTime.get();
    }
}
