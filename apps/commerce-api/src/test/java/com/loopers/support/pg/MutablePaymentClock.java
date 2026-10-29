package com.loopers.support.pg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class MutablePaymentClock extends Clock {
    private final AtomicReference<Instant> current = new AtomicReference<>(Instant.EPOCH);

    public void set(Instant instant) {
        current.set(instant);
    }

    public void advance(Duration duration) {
        current.updateAndGet(now -> now.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(getZone()) ? this : Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
