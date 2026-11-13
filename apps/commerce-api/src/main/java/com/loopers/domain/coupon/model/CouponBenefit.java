package com.loopers.domain.coupon.model;

import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

/** 캠페인과 발급 쿠폰이 공유하는 불변 혜택 값. 실제 할인 계산은 수행하지 않는다. */
public record CouponBenefit(String description, long discountAmount) {
    public CouponBenefit {
        if (description == null) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_BENEFIT);
        }
        description = description.strip();
        if (description.isEmpty() || description.codePointCount(0, description.length()) > 500) {
            throw new DomainException(DomainErrorCode.COUPON_INVALID_BENEFIT);
        }
        Money.positive(discountAmount);
    }
}
