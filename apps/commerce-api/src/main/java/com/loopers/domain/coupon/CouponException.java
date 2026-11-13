package com.loopers.domain.coupon;

public class CouponException extends RuntimeException {

    public enum Reason {
        SOLD_OUT,
        INACTIVE,
        CAMPAIGN_NOT_FOUND,
        RESERVATION_NOT_FOUND,
        ADMISSION_UNAVAILABLE
    }

    private final Reason reason;

    public CouponException(Reason reason) {
        this(reason, null);
    }

    public CouponException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
