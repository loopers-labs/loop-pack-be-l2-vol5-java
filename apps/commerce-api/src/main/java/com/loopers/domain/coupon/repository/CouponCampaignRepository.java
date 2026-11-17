package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.model.CouponCampaign;
import java.util.Optional;

public interface CouponCampaignRepository {
    CouponCampaign save(CouponCampaign campaign);

    Optional<CouponCampaign> findById(long campaignId);

    /** 호출자 TX가 끝날 때까지 캠페인의 현재 행을 배타적으로 잠근다. */
    Optional<CouponCampaign> findByIdForUpdate(long campaignId);

    /** 발급 TX 마지막 현재 행 잠금. 초기 잠금 대기는 1초이며 호출 뒤 연결의 기존 설정을 복구한다. */
    Optional<CouponCampaign> findByIdForIssue(long campaignId);
}
