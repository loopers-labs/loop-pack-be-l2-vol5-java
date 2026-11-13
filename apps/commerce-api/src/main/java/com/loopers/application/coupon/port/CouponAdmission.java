package com.loopers.application.coupon.port;

import java.util.Optional;

public interface CouponAdmission {
    Optional<LocalPermit> tryAcquire();
    int capacity();
    int available();

    interface LocalPermit {
        boolean release();
    }
}
