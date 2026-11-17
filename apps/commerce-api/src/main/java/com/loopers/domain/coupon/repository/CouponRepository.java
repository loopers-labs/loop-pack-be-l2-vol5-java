package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.model.Coupon;
import java.util.Optional;

public interface CouponRepository {
    /** 신규 결과에 ID 반영. 사용자/캠페인 충돌만 DuplicateCouponIssueException으로 구분한다. 다른 제약은 전파한다. */
    Coupon save(Coupon coupon);

    Optional<Coupon> findByCampaignIdAndUserId(long campaignId, long userId);
}
