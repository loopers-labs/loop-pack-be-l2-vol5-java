package com.loopers.domain.coupon;

public interface CouponPublishSlots {

    boolean tryAcquire();

    void release();
}
