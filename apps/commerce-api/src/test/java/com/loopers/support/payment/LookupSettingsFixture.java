package com.loopers.support.payment;

import com.loopers.infrastructure.payment.LookupResilienceSettings;

import java.time.Duration;

/** application.yml과 같은 조회 retry·회로 값. Spring 없이 provider를 직접 만드는 테스트가 쓴다. */
public final class LookupSettingsFixture {

    public static final LookupResilienceSettings LEARNING = new LookupResilienceSettings(
        Duration.ofSeconds(2), Duration.ofMillis(1_100),
        3, Duration.ofMillis(100), 2.0,
        4, 4, 50, Duration.ofSeconds(10), 2);

    private LookupSettingsFixture() {}
}
