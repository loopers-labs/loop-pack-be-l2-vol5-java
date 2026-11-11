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

    record Opened(String epoch, int cleanedTokens) {
    }

    Result tryAcquire();

    void release(String token);

    Opened open();

    void close();

    boolean isOpen();
}
