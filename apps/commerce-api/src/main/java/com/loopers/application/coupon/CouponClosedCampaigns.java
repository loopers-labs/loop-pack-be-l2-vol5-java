package com.loopers.application.coupon;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 다시 풀리지 않는 마감(최종 매진·종료)을 이 서버 메모리에 기억한다. 기억하는 동안 신청은 Redis에 가지 않는다.
 * 서버마다 따로 기억하므로, 다른 서버가 다시 준비한 캠페인은 기억 시간(ttl)이 지난 뒤 Redis에 다시 확인해 알게 된다.
 */
@Component
public class CouponClosedCampaigns {
    private record Closed(String rejection, long untilNanos) {}

    private final Map<Long, Closed> closed = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final long ttlNanos;
    private final LongSupplier nanoTime;

    @Autowired
    public CouponClosedCampaigns(@Value("${commerce.coupon.closed-cache.enabled:true}") boolean enabled,
        @Value("${commerce.coupon.closed-cache.ttl:10s}") Duration ttl) {
        this(enabled, ttl, System::nanoTime);
    }

    CouponClosedCampaigns(boolean enabled, Duration ttl, LongSupplier nanoTime) {
        this.enabled = enabled;
        this.ttlNanos = ttl.toNanos();
        this.nanoTime = nanoTime;
    }

    /** 기억한 마감이 있으면 그 거절 문구. */
    public Optional<String> rejection(Long campaignId) {
        if (!enabled) return Optional.empty();
        Closed entry = closed.get(campaignId);
        if (entry == null) return Optional.empty();
        if (nanoTime.getAsLong() - entry.untilNanos() >= 0) {
            closed.remove(campaignId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.rejection());
    }

    public void remember(Long campaignId, String rejection) {
        if (enabled) closed.put(campaignId, new Closed(rejection, nanoTime.getAsLong() + ttlNanos));
    }

    /** 다시 준비한 캠페인은 이 서버에서 바로 잊는다. */
    public void forget(Long campaignId) {
        closed.remove(campaignId);
    }
}
