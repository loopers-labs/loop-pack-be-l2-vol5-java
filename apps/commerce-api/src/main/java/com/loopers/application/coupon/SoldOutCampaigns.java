package com.loopers.application.coupon;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SoldOutCampaigns {

    private final Set<Long> campaignIds = ConcurrentHashMap.newKeySet();

    public boolean contains(Long campaignId) {
        return campaignIds.contains(campaignId);
    }

    public void mark(Long campaignId) {
        campaignIds.add(campaignId);
    }

    public void clear() {
        campaignIds.clear();
    }
}
