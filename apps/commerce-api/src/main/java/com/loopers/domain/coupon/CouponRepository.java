package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponRepository {
    Coupon save(Coupon coupon);

    /** 저장하고 즉시 DB 에 반영한다. 유일 제약 충돌을 이 호출에서 드러낸다. */
    Coupon insert(Coupon coupon);

    Optional<Coupon> findById(Long id);

    Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId);
}
