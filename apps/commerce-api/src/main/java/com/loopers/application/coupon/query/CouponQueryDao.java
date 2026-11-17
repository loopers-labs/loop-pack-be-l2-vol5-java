package com.loopers.application.coupon.query;

import java.util.List;
import java.util.Optional;

public interface CouponQueryDao {
    /** 인증된 사용자 ID로 조회하며 발급 시각, 쿠폰 ID 내림차순의 전체 목록을 반환한다. */
    List<CouponView> findCoupons(long userId);

    /** 해당 사용자와 캠페인에 저장된 발급 결과. 발급 가능 여부를 다시 판단하지 않는다. */
    Optional<CouponView> findIssuedCoupon(long userId, long campaignId);
}
