package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/** 캠페인과 쿠폰이 같은 혜택 범위 검사를 공유한다. */
final class CouponBenefits {

    private CouponBenefits() {}

    static void require(CouponBenefitType type, long value) {
        if (type == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "혜택 유형 필수");
        }
        if (!type.accepts(value)) {
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "혜택 값이 유형의 범위를 벗어났습니다. [type = " + type + ", value = " + value + "]");
        }
    }
}
