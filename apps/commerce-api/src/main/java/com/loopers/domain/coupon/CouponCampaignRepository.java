package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(Long id);

    /**
     * issued_count < total_quota 일 때만 issued_count 를 1 올린다. 총량 판정을 DB 단일 문장 안에서 원자적으로 한다 (설계 §6 D8).
     *
     * @return 갱신된 행 수. 0 이면 캠페인이 없거나 총량이 소진됐다 (SOLD_OUT)
     */
    int increaseIssuedCountIfAvailable(Long campaignId);
}
