package com.loopers.coupon.domain;

import java.util.Optional;

public interface CouponCampaignRepository {

    Optional<CouponCampaign> findForIssueById(Long campaignId);
}
