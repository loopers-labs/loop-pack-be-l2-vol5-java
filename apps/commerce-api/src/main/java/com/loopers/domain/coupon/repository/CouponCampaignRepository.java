package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.entity.CouponCampaign;

import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long campaignId);
}
