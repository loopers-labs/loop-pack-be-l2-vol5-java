package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 캠페인이 신규 발급을 받을 수 없는 사유를 함께 전달한다.
 * 동기 발급 API에서는 기존과 같은 409와 메시지로 응답하고, 대기열 worker는 사유로 작업 결과를 구분한다.
 */
public class CouponNotIssuableException extends CoreException {

    private final Reason reason;

    public CouponNotIssuableException(Reason reason) {
        super(ErrorType.CONFLICT, reason.message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        SOLD_OUT("쿠폰이 모두 발급된 캠페인입니다."),
        OUT_OF_PERIOD("쿠폰 발급 기간이 아닙니다.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
