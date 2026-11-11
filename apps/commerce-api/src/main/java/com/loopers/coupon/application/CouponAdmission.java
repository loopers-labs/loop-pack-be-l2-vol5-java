package com.loopers.coupon.application;

import java.util.Optional;

/** Coordinates one active coupon issuance permit. */
public interface CouponAdmission {

    Optional<String> acquire();

    void release(String token);
}
