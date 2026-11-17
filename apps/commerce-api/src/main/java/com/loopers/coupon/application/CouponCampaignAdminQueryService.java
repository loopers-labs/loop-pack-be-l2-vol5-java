package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponCampaignAdminInfo;
import com.loopers.coupon.application.port.out.CouponCampaignPort;
import com.loopers.coupon.application.port.out.CouponPort;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class CouponCampaignAdminQueryService {

    private final CouponCampaignPort couponCampaignPort;
    private final CouponPort couponPort;

    public CouponCampaignAdminInfo get(Long campaignId) {
        CouponCampaign campaign = couponCampaignPort.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[campaignId = " + campaignId + "] 캠페인을 찾을 수 없습니다."));
        return CouponCampaignAdminInfo.of(campaign, couponPort.findByCampaignId(campaignId));
    }
}
