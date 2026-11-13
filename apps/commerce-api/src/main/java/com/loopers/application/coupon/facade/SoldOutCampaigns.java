package com.loopers.application.coupon.facade;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 인스턴스가 품절을 본 캠페인. 품절은 되돌아가지 않으므로(8주차 D-125) 한 번 보면 계속 기억한다.
 * 기억한 캠페인의 POST 는 Redis 를 거치지 않고 바로 SOLD_OUT 이다 (8주차 D-170)
 */
@Component
public class SoldOutCampaigns {

    private final Set<Long> campaignIds = ConcurrentHashMap.newKeySet();

    public boolean contains(Long campaignId) {
        return campaignIds.contains(campaignId);
    }

    public void remember(Long campaignId) {
        campaignIds.add(campaignId);
    }

    /** 운영에서는 부르지 않는다. 테스트는 DB 를 비우면 캠페인 id 가 다시 쓰여 기억을 지워야 한다 */
    public void clear() {
        campaignIds.clear();
    }
}
