package com.loopers.domain.coupon;

import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    /** 즉시 insert해서 UNIQUE 위반이 이 호출 안에서 드러나게 한다. */
    CouponModel saveAndFlush(CouponModel coupon);

    Optional<CouponModel> findByCampaignIdAndUserId(Long campaignId, Long userId);

    /** 발급 시각 내림차순, id 내림차순. 본인 쿠폰만 돌려준다. */
    List<CouponModel> findAllByUserIdOrderByIssuedAtDesc(Long userId);
}
