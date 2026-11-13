package com.loopers.domain.coupon.exception;

/** 마지막 캠페인 현재 행 잠금의 짧은 대기 시간이 끝났다. 업무 TX 전체는 rollback한다. */
public class CouponIssueLockBusyException extends RuntimeException {
    public CouponIssueLockBusyException(Throwable cause) {
        super("쿠폰 캠페인 잠금을 제한 시간 안에 획득하지 못했습니다.", cause);
    }
}
