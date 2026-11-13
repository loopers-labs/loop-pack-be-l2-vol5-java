package com.loopers.domains.coupon.app;

import com.loopers.common.error.NotFoundException;

public class CouponCampaignNotFoundException extends NotFoundException {
    public CouponCampaignNotFoundException(long campaignId) {
        super("쿠폰 캠페인을 찾을 수 없습니다. campaignId=" + campaignId);
    }
}
