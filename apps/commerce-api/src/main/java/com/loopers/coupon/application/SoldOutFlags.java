package com.loopers.coupon.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 서버 메모리의 품절 표시 (D-4-6). Redis의 "당첨자 Set 크기 ≥ 당첨자 수"를 본 서버가 켜는 사본이다.
 * 5초 뒤 사라져 다시 Redis를 본다 — Redis 복구로 품절이 풀리는 경우를 놓치지 않게.
 */
@Component
public class SoldOutFlags {

    static final Duration TTL = Duration.ofSeconds(5);

    private final Cache<Long, Boolean> soldOut = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();

    public boolean isSoldOut(Long campaignId) {
        return soldOut.getIfPresent(campaignId) != null;
    }

    public void markSoldOut(Long campaignId) {
        soldOut.put(campaignId, Boolean.TRUE);
    }

    /** 운영 도구·테스트용: 모든 품절 표시를 지운다 (다음 요청이 Redis를 다시 본다) */
    public void clear() {
        soldOut.invalidateAll();
    }
}
