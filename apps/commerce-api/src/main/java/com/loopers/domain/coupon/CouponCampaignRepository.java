package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaign save(CouponCampaign couponCampaign);

    Optional<CouponCampaign> findById(Long id);

    /** 발급 수가 한도보다 작을 때만 1 올린다. 바뀐 행 수(0 또는 1)를 돌려준다. */
    int increaseIssuedCount(Long id);
}
