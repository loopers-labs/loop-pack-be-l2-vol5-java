package com.loopers.coupon.domain;

import java.time.ZonedDateTime;

/**
 * 서버 메모리에 두는 열린 캠페인 정보 (D-4-3). 판정 Lua에 넘길 당첨자 수·줄 길이 제한과 기간만 담는다.
 */
public record CampaignSnapshot(Long id, int totalQuantity, int queueCapacity, ZonedDateTime issueStartAt, ZonedDateTime issueEndAt) {

    public static CampaignSnapshot of(CouponCampaign campaign) {
        return new CampaignSnapshot(campaign.getId(), campaign.getTotalQuantity(), campaign.getQueueCapacity(),
            campaign.getIssueStartAt(), campaign.getIssueEndAt());
    }

    public boolean isOpenAt(ZonedDateTime now) {
        return !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
    }
}
