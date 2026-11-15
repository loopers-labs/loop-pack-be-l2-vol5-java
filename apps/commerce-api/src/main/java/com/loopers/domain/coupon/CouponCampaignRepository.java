package com.loopers.domain.coupon;

import java.util.Optional;

public interface CouponCampaignRepository {
    /** 같은 캠페인의 발급은 이 행 잠금을 획득한 뒤에만 진행한다. */
    Optional<CouponCampaign> lockById(long campaignId);

    /** lockById로 얻은 잠금을 보유한 트랜잭션 안에서 호출한다. */
    void increaseIssuedCount(long campaignId);
}
