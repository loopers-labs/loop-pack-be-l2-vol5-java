package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 캠페인 상태를 DB 에서 먼저 바꿔 commit 한다. Redis 는 그 뒤에 맞춘다 (8주차 D-166) */
@RequiredArgsConstructor
@Component
public class CouponCampaignSteps {

    private final CouponCampaignRepository couponCampaignRepository;

    @Transactional
    public CouponCampaign open(Long campaignId) {
        CouponCampaign campaign = find(campaignId);
        campaign.open();
        return campaign;
    }

    @Transactional
    public CouponCampaign close(Long campaignId) {
        CouponCampaign campaign = find(campaignId);
        campaign.close();
        return campaign;
    }

    private CouponCampaign find(Long campaignId) {
        return couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(CouponErrorCode.CAMPAIGN_NOT_FOUND));
    }
}
