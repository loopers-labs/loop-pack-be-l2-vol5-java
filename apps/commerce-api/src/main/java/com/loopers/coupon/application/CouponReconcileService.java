package com.loopers.coupon.application;

import com.loopers.coupon.application.port.out.CouponCampaignPort;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.domain.CampaignSnapshot;
import com.loopers.coupon.domain.CouponCampaign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 앞으로 채우기 (D-2): Redis 당첨자 Set이 원본이고 outbox가 그 명단을 따라잡는다.
 * Set에 있는데 outbox에 없는 사람(API가 Lua 뒤 기록 전에 죽음, 기록 실패 뒤 다시 오지 않음)에게 기록을 채운다.
 * 여러 서버가 동시에 해도 UNIQUE(campaign_id, user_id)가 한 행만 남긴다. 느린 API가 막 기록하려던 참이어도 늦은 쪽이 중복으로 끝난다.
 * <p>
 * 마감 + 10분 뒤 Redis 키를 정리한다 (D-4-6). 10분은 마감 직전 당첨자의 기록을 맞춰 보기가 채울 여유다.
 */
@Slf4j
@Service
public class CouponReconcileService {

    static final Duration CLEANUP_DELAY = Duration.ofMinutes(10);
    static final Duration CLEANUP_WINDOW = Duration.ofHours(1);

    private final OpenCampaigns openCampaigns;
    private final CouponCampaignPort campaignPort;
    private final CouponGatePort gate;
    private final CouponIssueOutboxPort outboxPort;
    private final Clock clock;

    public CouponReconcileService(OpenCampaigns openCampaigns, CouponCampaignPort campaignPort, CouponGatePort gate,
                                  CouponIssueOutboxPort outboxPort, Clock clock) {
        this.openCampaigns = openCampaigns;
        this.campaignPort = campaignPort;
        this.gate = gate;
        this.outboxPort = outboxPort;
        this.clock = clock;
    }

    public void run() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (CampaignSnapshot campaign : openCampaigns.all()) {
            safely(campaign.id(), () -> fillMissingRecords(campaign.id()));
        }
        // 마감 뒤 10분 안: 아직 채울 수 있다. 10분이 지나면 정리한다
        for (CouponCampaign ended : campaignPort.findEndedBetween(now.minus(CLEANUP_DELAY), now)) {
            safely(ended.getId(), () -> fillMissingRecords(ended.getId()));
        }
        for (CouponCampaign ended : campaignPort.findEndedBetween(now.minus(CLEANUP_WINDOW), now.minus(CLEANUP_DELAY))) {
            safely(ended.getId(), () -> {
                fillMissingRecords(ended.getId());
                gate.cleanup(ended.getId());
            });
        }
    }

    /**
     * @return 새로 채운 기록 수
     */
    public int fillMissingRecords(Long campaignId) {
        Set<Long> winners = gate.winners(campaignId);
        if (winners.isEmpty()) {
            return 0;
        }
        Set<Long> missing = new HashSet<>(winners);
        missing.removeAll(outboxPort.findRecordedUserIds(campaignId, winners));
        int filled = 0;
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (Long userId : missing) {
            if (outboxPort.appendIfAbsent(campaignId, userId, now)) {
                filled++;
            }
        }
        if (filled > 0) {
            log.warn("[COUPON_RECONCILED] 당첨됐지만 기록이 없던 {}명의 기록을 채웠습니다. campaignId={}", filled, campaignId);
        }
        return filled;
    }

    private static void safely(Long campaignId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("맞춰 보기 실패 — 다음 주기에 다시 한다. campaignId={}", campaignId, e);
        }
    }
}
