package com.loopers.domain.coupon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface CouponRepository {
    Coupon save(Coupon coupon);

    /** 저장하고 즉시 DB 에 반영한다. 유일 제약 충돌을 이 호출에서 드러낸다. */
    Coupon insert(Coupon coupon);

    Optional<Coupon> findById(Long id);

    Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    /** 사용자의 쿠폰을 발급 시각 내림차순, 같으면 couponId 내림차순으로 조회한다. */
    Page<Coupon> findAllByUserId(Long userId, Pageable pageable);
}
