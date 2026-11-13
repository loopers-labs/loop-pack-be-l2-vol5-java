package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaign save(CouponCampaign couponCampaign);

    Optional<CouponCampaign> findById(Long id);
}
