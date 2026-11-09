package com.loopers.application.promotion.query;

import java.util.List;

/** 쿠폰 조회 전용 Repository. {@link CouponView} 로 바로 반환한다 (DR-31). */
public interface CouponQueryRepository {
    /** 사용자의 발급 쿠폰 전부. 최근 발급 순(id 내림차순). */
    List<CouponView.MyCoupon> findMyCoupons(Long userId);
}
