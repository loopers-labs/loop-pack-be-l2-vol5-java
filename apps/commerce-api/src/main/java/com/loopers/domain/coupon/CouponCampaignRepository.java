package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {

    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long campaignId);

    boolean increaseIssuedCountWithinQuota(Long campaignId);
}
