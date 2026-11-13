package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;

/** campaignName 은 조회 시점 이름, 혜택은 발급 당시 값이다. */
public record OwnedCoupon(
    Long couponId,
    Long campaignId,
    String campaignName,
    CouponBenefitType benefitType,
    long benefitValue,
    ZonedDateTime createdAt
) {
    public OwnedCoupon {
        if (couponId == null || campaignId == null || campaignName == null || benefitType == null || createdAt == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "쿠폰 조회 결과가 손상됐습니다. [couponId = " + couponId + ", campaignId = " + campaignId + "]");
        }
    }
}
