package com.loopers.domain.coupon.exception;

/** 사용자/캠페인 유일 제약 충돌. 실패한 업무 TX가 끝난 뒤 기존 결과를 조회해야 한다. */
public class DuplicateCouponIssueException extends RuntimeException {
    public DuplicateCouponIssueException(Throwable cause) {
        super("같은 사용자가 캠페인의 쿠폰을 이미 발급받았습니다.", cause);
    }
}
