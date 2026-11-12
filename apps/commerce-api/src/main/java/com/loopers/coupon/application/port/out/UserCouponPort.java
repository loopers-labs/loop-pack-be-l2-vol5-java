package com.loopers.coupon.application.port.out;

import com.loopers.coupon.domain.UserCoupon;

import java.util.List;

public interface UserCouponPort {

    /** 내 쿠폰 목록 — 최근 발급 순 */
    List<UserCoupon> findByUserId(Long userId);

    /** 이 캠페인에서 받은 쿠폰들 (발급 API의 "이미 발급" 응답, D-7) */
    List<UserCoupon> findByCampaignIdAndUserId(Long campaignId, Long userId);
}
