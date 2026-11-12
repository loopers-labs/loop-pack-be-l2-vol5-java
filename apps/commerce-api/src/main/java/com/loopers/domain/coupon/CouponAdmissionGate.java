package com.loopers.domain.coupon;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CouponAdmissionGate {

    private final ConcurrentHashMap<Long, Boolean> gates = new ConcurrentHashMap<>();

    public void open(long campaignId) {
        gates.put(campaignId, true);
    }

    public void close(long campaignId) {
        gates.put(campaignId, false);
    }

    public void forceClose(long campaignId) {
        gates.put(campaignId, false);
    }

    public boolean isOpen(long campaignId) {
        return Boolean.TRUE.equals(gates.get(campaignId));
    }

    public Set<Long> getAllOpenCampaignIds() {
        Set<Long> open = ConcurrentHashMap.newKeySet();
        gates.forEach((id, isOpen) -> {
            if (Boolean.TRUE.equals(isOpen)) open.add(id);
        });
        return open;
    }
}
