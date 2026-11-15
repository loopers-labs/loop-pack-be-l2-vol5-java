package com.loopers.domain.coupon;

import java.time.Instant;
import java.util.Optional;

public interface CouponCampaignRepository {
    Optional<CouponCampaignModel> find(Long campaignId);

    /** 활성이고 now에 끝나지 않았고 한도가 남았을 때만 발급 수를 1 올린다. 올렸으면 true. 캠페인 행 잠금은 TX 끝까지 쥔다. */
    boolean tryRecordIssue(Long campaignId, Instant now);

    /** 활성이고 at에 끝나지 않았고 count장이 모두 한도 안일 때만 발급 수를 count만큼 올린다. 하나라도 넘치면 올리지 않는다. */
    boolean tryRecordIssues(Long campaignId, int count, Instant at);

}
