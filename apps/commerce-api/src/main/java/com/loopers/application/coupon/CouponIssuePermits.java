package com.loopers.application.coupon;

public interface CouponIssuePermits {

    boolean tryAcquire(String token, int capacity);

    long release(String token);

    boolean isEmpty();
}
