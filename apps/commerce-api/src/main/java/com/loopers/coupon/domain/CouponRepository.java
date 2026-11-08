package com.loopers.coupon.domain;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {

    Coupon save(Coupon coupon);

    Optional<Coupon> findByCampaignIdAndUserId(Long campaignId, Long userId);

    List<Coupon> findAllByUserId(Long userId);
}
