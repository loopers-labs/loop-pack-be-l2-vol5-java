package com.loopers.application.coupon;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 기억한 마감은 정한 시간이 지나면 잊어, 다른 서버가 다시 준비한 캠페인을 늦어도 그 시간 안에 다시 확인한다. */
class CouponClosedCampaignsTest {
    private final AtomicLong now = new AtomicLong();
    private final CouponClosedCampaigns closed = new CouponClosedCampaigns(true, Duration.ofSeconds(10), now::get);

    @Test
    void remembersUntilTimeToLiveThenAsksAgain() {
        closed.remember(42L, CouponRejections.CLOSED_SOLD_OUT);
        assertThat(closed.rejection(42L)).contains(CouponRejections.CLOSED_SOLD_OUT);

        now.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThat(closed.rejection(42L)).isEmpty();
    }

    @Test
    void forgetRemovesImmediately() {
        closed.remember(42L, CouponRejections.CLOSED_ENDED);
        closed.forget(42L);
        assertThat(closed.rejection(42L)).isEmpty();
    }

    @Test
    void disabledNeverRemembers() {
        CouponClosedCampaigns off = new CouponClosedCampaigns(false, Duration.ofSeconds(10), now::get);
        off.remember(42L, CouponRejections.CLOSED_SOLD_OUT);
        assertThat(off.rejection(42L)).isEmpty();
    }
}
