package com.loopers.domain.coupon;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface CouponCampaignRepository {

    CouponCampaignModel save(CouponCampaignModel campaign);

    /** 조건부 UPDATE. 쓰기 TX 의 첫 업무 SQL 이며 0 건은 원인을 구분하지 않는다. */
    int reserveOne(Long campaignId, ZonedDateTime now);

    /** Entity 를 영속성 컨텍스트에 올리지 않는다. */
    Optional<CouponCampaignIssueData> findIssueData(Long campaignId);

    /** 쓰기 TX 의 선행 로드에 쓰지 않는다. */
    Optional<CouponCampaignModel> findById(Long campaignId);
}
