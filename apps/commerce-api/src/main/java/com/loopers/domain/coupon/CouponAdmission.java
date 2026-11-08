package com.loopers.domain.coupon;

public interface CouponAdmission {

    sealed interface Result {

        record Admitted(String token) implements Result {
        }

        record Full() implements Result {
        }

        record Unavailable() implements Result {
        }
    }

    Result tryAcquire();

    void release(String token);
}
