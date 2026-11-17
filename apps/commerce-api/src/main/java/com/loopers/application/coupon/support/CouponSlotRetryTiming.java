package com.loopers.application.coupon.support;

import org.springframework.stereotype.Component;

@Component
public class CouponSlotRetryTiming {
    public long now() { return System.nanoTime(); }

    public void pause() throws InterruptedException { Thread.sleep(50); }
}
