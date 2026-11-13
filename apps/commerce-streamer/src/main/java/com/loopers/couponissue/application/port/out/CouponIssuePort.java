package com.loopers.couponissue.application.port.out;

import com.loopers.couponissue.domain.IssuableCoupon;
import com.loopers.couponissue.domain.UserCoupon;

import java.util.List;

public interface CouponIssuePort {

    boolean hasIssued(Long campaignId, Long userId);

    List<IssuableCoupon> findCoupons(Long campaignId);

    /**
     * 바로 flush한다 — UNIQUE(coupon_id, user_id) 위반을 발급 수 UPDATE 전에 드러낸다.
     */
    void saveAll(List<UserCoupon> userCoupons);

    /**
     * 발급 수 &lt; 당첨자 수일 때만 +1. 바뀐 행이 있으면 true (D-5 조건부 UPDATE).
     */
    boolean increaseIssuedCountIfAvailable(Long campaignId);
}
