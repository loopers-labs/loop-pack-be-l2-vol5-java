package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.model.CouponSlot;
import java.util.Optional;

public interface CouponSlotRepository {
    CouponSlot save(CouponSlot slot);

    /** 같은 TX에서 미발급 슬롯 하나를 잠그며 잠긴 행은 건너뛴다. 빈 결과가 곧 품절은 아니다. */
    Optional<CouponSlot> findAvailableForUpdateSkipLocked(long campaignId);

    /** 이미 발급한 슬롯도 포함한 전체 준비 수. 활성화 검증은 같은 TX에서 수행한다. */
    long countByCampaignId(long campaignId);
}
