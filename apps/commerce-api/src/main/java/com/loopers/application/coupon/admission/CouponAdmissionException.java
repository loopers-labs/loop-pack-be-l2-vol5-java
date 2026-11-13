package com.loopers.application.coupon.admission;

/** 수용 상태를 확인할 수 없다(접근 실패·timeout·알 수 없는 응답). 수용됐는지 모르므로 신규 발급을 시작하지 않는다. */
public class CouponAdmissionException extends RuntimeException {

    public CouponAdmissionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CouponAdmissionException(String message) {
        super(message);
    }
}
