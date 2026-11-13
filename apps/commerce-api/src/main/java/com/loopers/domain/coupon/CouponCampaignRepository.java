package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponCampaignRepository {

    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long campaignId);

    List<Long> findActiveIds();

    boolean increaseIssuedCountWithinQuota(Long campaignId, int count);
}
