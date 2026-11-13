package com.loopers.coupon.application.port.out;

import com.loopers.coupon.domain.Coupon;

import java.util.List;

public interface CouponPort {

    List<Coupon> saveAll(List<Coupon> coupons);

    /**
     * 캠페인에 담긴 쿠폰을 id 순서로 (쿠폰팩).
     */
    List<Coupon> findByCampaignId(Long campaignId);

    List<Coupon> findAllByIds(List<Long> ids);
}
