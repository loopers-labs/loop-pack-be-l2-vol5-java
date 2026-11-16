package com.loopers.coupon.domain;

import java.util.Optional;

public interface CouponCampaignRepository {

    Optional<CouponCampaign> findById(Long campaignId);

    Optional<CouponCampaign> findForIssueById(Long campaignId);
}
