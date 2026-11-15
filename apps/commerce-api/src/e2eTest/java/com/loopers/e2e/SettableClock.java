package com.loopers.e2e;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 단계마다 시각을 정하는 UTC clock. 준비(확정)와 복구가 서로 다른 때에 일어났음을 재현한다. */
final class SettableClock extends Clock {

    private volatile Instant now;

    SettableClock(Instant start) {
        this.now = start;
    }

    void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
