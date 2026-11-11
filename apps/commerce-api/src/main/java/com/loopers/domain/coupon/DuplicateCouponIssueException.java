package com.loopers.domain.coupon;

public class DuplicateCouponIssueException extends RuntimeException {

    public DuplicateCouponIssueException(Throwable cause) {
        super("같은 캠페인에서 이미 쿠폰을 발급받았습니다.", cause);
    }
}
