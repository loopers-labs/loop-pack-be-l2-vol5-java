package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    Coupon save(Coupon coupon);

    Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    /**
     * 본인 보유 목록. issuedAt desc · id desc (설계 §8-1).
     */
    List<Coupon> findAllByUserId(Long userId);
}
