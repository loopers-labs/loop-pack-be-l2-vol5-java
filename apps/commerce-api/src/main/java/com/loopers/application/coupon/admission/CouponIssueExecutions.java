package com.loopers.application.coupon.admission;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이 인스턴스가 시작한 발급 실행 수와, 아직 반환을 확인하지 못한 자기 token. 재개할 때 진행 중 실행이 없는지 확인하고
 * 자기 범위의 token 만 정리하는 데 쓴다. 다른 인스턴스의 실행은 알지 못한다.
 */
@Component
public class CouponIssueExecutions {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<String, Long> unreleasedTokens = new ConcurrentHashMap<>();

    public void start() {
        inFlight.incrementAndGet();
    }

    public void end() {
        inFlight.decrementAndGet();
    }

    public int inFlight() {
        return inFlight.get();
    }

    /** Redis 에 들어갔을 수 있는 token 을 기록한다. 응답이 불확실해도 남겨 두어 재개 때 정리한다. */
    public void hold(Long campaignId, String token) {
        unreleasedTokens.put(token, campaignId);
    }

    /** 반환됐거나 Redis 에 들어가지 않은 것이 확실한 token 을 지운다. */
    public void release(String token) {
        unreleasedTokens.remove(token);
    }

    public List<String> unreleasedTokens(Long campaignId) {
        return unreleasedTokens.entrySet().stream()
            .filter(entry -> entry.getValue().equals(campaignId))
            .map(Map.Entry::getKey)
            .toList();
    }
}
