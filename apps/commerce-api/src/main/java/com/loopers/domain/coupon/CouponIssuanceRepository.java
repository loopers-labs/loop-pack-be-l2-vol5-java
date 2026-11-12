package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponIssuanceRepository {
    CouponIssuanceModel save(CouponIssuanceModel issuance);
    Optional<CouponIssuanceModel> findByCampaignIdAndUserId(long campaignId, long userId);
}
