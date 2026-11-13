package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.OwnedCoupon;

/** created 는 이 요청의 쓰기 TX commit 을 확인한 경우만 true 다. */
public record CouponIssueResult(
    Long couponId,
    Long campaignId,
    String campaignName,
    CouponBenefitType benefitType,
    long benefitValue,
    boolean created
) {

    static CouponIssueResult stored(OwnedCoupon coupon) {
        return new CouponIssueResult(coupon.couponId(), coupon.campaignId(), coupon.campaignName(),
            coupon.benefitType(), coupon.benefitValue(), false);
    }
}
