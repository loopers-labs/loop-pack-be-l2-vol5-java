package com.loopers.application.coupon.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CouponQueueWorkerStateTest {
    @Test
    void 기본_시작은_닫혀있고_로컬에서_명시한_경우에만_열린다() {
        var local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThat(new CouponQueueWorkerState(local, false).acceptingNewRequests()).isFalse();
        assertThat(new CouponQueueWorkerState(local, true).acceptingNewRequests()).isTrue();
    }

    @Test
    void 운영_프로필이나_프로필_혼합은_명시적으로_열더라도_닫힌다() {
        var production = new MockEnvironment();
        production.setActiveProfiles("prod");
        var mixed = new MockEnvironment();
        mixed.setActiveProfiles("local", "prod");

        assertThat(new CouponQueueWorkerState(production, true).acceptingNewRequests()).isFalse();
        assertThat(new CouponQueueWorkerState(mixed, true).acceptingNewRequests()).isFalse();
        assertThat(new CouponQueueWorkerState(new MockEnvironment(), true).acceptingNewRequests()).isFalse();
    }

    @Test
    void 장애_후_새_인스턴스는_기본적으로_닫혀있다() {
        var initial = new CouponQueueWorkerState(true);
        initial.pause();
        var local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThat(initial.acceptingNewRequests()).isFalse();
        assertThat(new CouponQueueWorkerState(local, false).acceptingNewRequests()).isFalse();
    }
}
