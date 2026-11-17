package com.loopers.coupon.application;

import com.loopers.coupon.application.port.out.CouponCampaignPort;
import com.loopers.coupon.domain.CampaignSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 서버마다 메모리에 두는 열린 캠페인 목록 (D-4-3). 입장 스케줄러가 10초마다 DB에서 다시 읽는다.
 * 목록에 없으면 DB를 한 번 본다 — 막 열린 캠페인이 다음 갱신 전에 409가 되지 않게.
 */
@Component
public class OpenCampaigns {

    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);

    private final CouponCampaignPort campaignPort;
    private final Clock clock;
    private volatile Map<Long, CampaignSnapshot> open = Map.of();
    private volatile long refreshedAtNanos = 0;

    public OpenCampaigns(CouponCampaignPort campaignPort, Clock clock) {
        this.campaignPort = campaignPort;
        this.clock = clock;
    }

    public Collection<CampaignSnapshot> all() {
        return open.values();
    }

    public void refreshIfStale() {
        if (System.nanoTime() - refreshedAtNanos >= REFRESH_INTERVAL.toNanos()) {
            refresh();
        }
    }

    public void refresh() {
        open = campaignPort.findOpenAt(now()).stream()
            .map(CampaignSnapshot::of)
            .collect(Collectors.toUnmodifiableMap(CampaignSnapshot::id, Function.identity()));
        refreshedAtNanos = System.nanoTime();
    }

    /**
     * 지금 발급 기간인 캠페인. 없는 캠페인이면 404, 기간이 아니면 409 CAMPAIGN_NOT_OPEN.
     */
    public CampaignSnapshot require(Long campaignId) {
        ZonedDateTime now = now();
        CampaignSnapshot cached = open.get(campaignId);
        if (cached != null && cached.isOpenAt(now)) {
            return cached;
        }
        CampaignSnapshot fresh = campaignPort.findById(campaignId)
            .map(CampaignSnapshot::of)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[campaignId = " + campaignId + "] 캠페인을 찾을 수 없습니다."));
        if (!fresh.isOpenAt(now)) {
            throw new CoreException(ErrorType.CAMPAIGN_NOT_OPEN);
        }
        Map<Long, CampaignSnapshot> next = new ConcurrentHashMap<>(open);
        next.put(campaignId, fresh);
        open = Map.copyOf(next);
        return fresh;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
