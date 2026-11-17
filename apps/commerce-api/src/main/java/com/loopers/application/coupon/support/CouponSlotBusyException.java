package com.loopers.application.coupon.support;

/** 쓰기 전 슬롯 선택이 비었다. 품절이 확정된 결과나 발급 예약이 아니다. */
public class CouponSlotBusyException extends RuntimeException {
    public CouponSlotBusyException() {
        super("남은 쿠폰 슬롯을 일시적으로 획득하지 못했습니다.");
    }
}
