package com.loopers.domain.coupon;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    Optional<CouponModel> findByCampaignIdAndUserId(Long campaignId, Long userId);

    CouponModel save(CouponModel coupon);

    /** 발급 시각 내림차순, 같은 시각이면 ID 내림차순. */
    List<CouponModel> findAllByUserId(Long userId);

    List<Long> findUserIdsByCampaignId(Long campaignId);

    /** 한 캠페인에서 주어진 사용자들의 쿠폰. 한 번의 조회로 확인한다. */
    List<CouponModel> findAllByCampaignIdAndUserIds(Long campaignId, Collection<Long> userIds);

    /** 여러 쿠폰을 INSERT 문 하나로 저장한다. 생성된 ID는 돌려주지 않는다. */
    void insertAll(List<CouponModel> coupons);
}
