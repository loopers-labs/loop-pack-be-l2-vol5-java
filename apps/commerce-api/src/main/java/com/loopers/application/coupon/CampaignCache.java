package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponService;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class CampaignCache {

    private final CouponService couponService;
    private final Map<Long, CouponCampaign> campaigns = new ConcurrentHashMap<>();

    public CouponCampaign get(Long campaignId) {
        CouponCampaign cached = campaigns.get(campaignId);
        if (cached != null) {
            return cached;
        }
        CouponCampaign loaded = couponService.findCampaign(campaignId)
            .orElseThrow(() -> new DomainException(DomainError.COUPON_CAMPAIGN_NOT_FOUND));
        campaigns.putIfAbsent(campaignId, loaded);
        return loaded;
    }

    public void clear() {
        campaigns.clear();
    }
}
