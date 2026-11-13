package com.loopers.application.coupon;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트 전용 시계. 시스템 시각에 오프셋을 더해 "시간이 흘렀다"를 sleep 없이 만든다.
 * 승격 TTL·자리 TTL·서킷 대기 판정이 모두 이 시계를 보므로, advance 로 만료 시각을 지나게 할 수 있다.
 */
class AdjustableClock extends Clock {

    private final Clock base = Clock.systemDefaultZone();
    private volatile Duration offset = Duration.ZERO;

    void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }
}
