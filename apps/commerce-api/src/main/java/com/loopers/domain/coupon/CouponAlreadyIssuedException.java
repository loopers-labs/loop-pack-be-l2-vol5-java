package com.loopers.domain.coupon;

/** 사용자와 캠페인 쌍의 DB 유일 제약과 충돌한 경우만 나타낸다. */
public class CouponAlreadyIssuedException extends RuntimeException {
    public CouponAlreadyIssuedException(Throwable cause) {
        super("같은 캠페인의 쿠폰이 이미 발급되었습니다.", cause);
    }
}
