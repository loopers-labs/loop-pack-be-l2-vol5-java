package com.loopers.application.coupon.support;

/** C04의 내부 수용 오류. HTTP 응답 연결은 C06에서 추가한다. */
public class CouponAdmissionException extends RuntimeException {
    public enum Code {
        COUPON_CAPACITY_EXCEEDED,
        COUPON_ADMISSION_UNAVAILABLE,
        COUPON_ISSUE_OUTCOME_UNKNOWN,
        COUPON_RECOVERY_REJECTED
    }

    private final Code errorCode;

    public CouponAdmissionException(Code errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public Code getErrorCode() { return errorCode; }
}
