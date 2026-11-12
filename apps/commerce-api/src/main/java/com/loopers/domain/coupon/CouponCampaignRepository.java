package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaignModel save(CouponCampaignModel campaign);
    Optional<CouponCampaignModel> findById(long id);
    int incrementIssuedCountIfAvailable(long id);
}
