package com.loopers.application.coupon;

public class CouponIssueSemaphoreUnavailableException extends RuntimeException {
    public CouponIssueSemaphoreUnavailableException() {
        super("Coupon issuance capacity cannot be confirmed");
    }

    public CouponIssueSemaphoreUnavailableException(Throwable cause) {
        super("Coupon issuance capacity cannot be confirmed", cause);
    }
}
