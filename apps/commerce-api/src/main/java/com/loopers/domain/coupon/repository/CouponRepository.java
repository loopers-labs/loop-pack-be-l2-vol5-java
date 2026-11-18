package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.entity.Coupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CouponRepository {
    /** 본인 쿠폰만, 발급 시각 desc, 식별자 desc (8주차 D-147) */
    Page<Coupon> findMyCoupons(Long userId, Pageable pageable);
}
