package com.loopers.application.coupon;

public class CouponPermitScriptException extends RuntimeException {

    public CouponPermitScriptException(Throwable cause) {
        super("쿠폰 처리 자리 스크립트를 실행할 수 없습니다.", cause);
    }
}
