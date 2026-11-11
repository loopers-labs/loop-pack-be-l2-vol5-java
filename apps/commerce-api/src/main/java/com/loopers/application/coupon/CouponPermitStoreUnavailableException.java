package com.loopers.application.coupon;

public class CouponPermitStoreUnavailableException extends RuntimeException {

    public CouponPermitStoreUnavailableException(Throwable cause) {
        super("쿠폰 처리 자리 상태를 확인할 수 없습니다.", cause);
    }
}
