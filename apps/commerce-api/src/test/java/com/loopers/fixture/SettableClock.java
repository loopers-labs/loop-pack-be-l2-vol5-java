package com.loopers.fixture;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트가 정한 순간을 돌려주고 읽은 횟수를 세는 Clock. 반복·동시 적용이 확정 시각을 다시 만들지 않는지 횟수로 확인한다.
 */
public class SettableClock extends Clock {

    private volatile Instant instant;
    private volatile ZoneId zone;
    private final AtomicInteger reads = new AtomicInteger();

    public SettableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void set(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public int reads() {
        return reads.get();
    }

    public void resetReads() {
        reads.set(0);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        reads.incrementAndGet();
        return instant;
    }
}
