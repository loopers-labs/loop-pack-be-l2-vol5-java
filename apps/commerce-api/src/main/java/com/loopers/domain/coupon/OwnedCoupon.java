package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;

/**
 * 본인 쿠폰 조회 결과. campaignName 은 조회 시점 이름, 혜택은 발급 당시 값, createdAt 은 DB 저장 시각이다.
 * 연결 캠페인이 없는 손상 데이터는 이 결과로 만들 수 없다.
 */
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
