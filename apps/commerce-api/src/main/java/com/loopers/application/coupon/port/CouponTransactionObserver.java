package com.loopers.application.coupon.port;

import com.loopers.application.coupon.support.CouponExecution;

public interface CouponTransactionObserver {
    void observe(CouponExecution execution);
}
