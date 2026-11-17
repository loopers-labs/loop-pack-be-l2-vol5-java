package com.loopers.application.coupon;

public class CouponSelectionUnavailableException extends RuntimeException {
    public CouponSelectionUnavailableException() {
        super("Coupon selection state is unavailable");
    }

    public CouponSelectionUnavailableException(Throwable cause) {
        super("Coupon selection state is unavailable", cause);
    }
}
