package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaignModel save(CouponCampaignModel campaign);

    /** 락 없이 읽는다. 빠른 거절과 결과 구분에 쓰며 최종 판정이 아니다. */
    Optional<CouponCampaignModel> findById(Long campaignId);

    /**
     * 활성이고 issued_count < total_quota일 때만 issued_count를 1 올린다.
     * 영향 행 수 1이면 true. 한도 판정을 이 한 문장이 원자적으로 맡는다.
     */
    boolean increaseIssuedCount(Long campaignId);
}
