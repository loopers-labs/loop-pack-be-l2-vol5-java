package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.OwnedCoupon;

/**
 * 발급 응답의 다섯 값과 신규 발급(created) 여부. created 는 이 요청의 쓰기 TX commit 을 확인한 경우만 true 다.
 * campaignName 은 응답을 만든 TX 에서 읽은 이름, 혜택은 쿠폰에 저장된 값이다.
 */
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
