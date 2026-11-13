package com.loopers.coupon.application;

import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponGatePort.AdmitResult;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.domain.CampaignSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * 입장 한 구간 (D-4-3). 모든 서버가 부르고, 구간 한도는 Redis 카운터가 센다.
 * 상태 키가 없으면(UNKNOWN: 캠페인 시작 또는 Redis 데이터 유실) 한 서버만 outbox로 당첨자 Set을 되살린다 (D-4-7 자동 복구).
 * 요청 경로(등록·조회·발급)도 UNKNOWN을 만나면 {@link #recover}를 부른다 — 캠페인이 막 열렸을 때 스케줄러를 기다리며 503을 주지 않게.
 */
@Slf4j
@Service
public class QueueAdmissionService {

    private final OpenCampaigns openCampaigns;
    private final SoldOutFlags soldOutFlags;
    private final CouponGatePort gate;
    private final CouponIssueOutboxPort outboxPort;
    private final Clock clock;

    public QueueAdmissionService(OpenCampaigns openCampaigns, SoldOutFlags soldOutFlags, CouponGatePort gate,
                                 CouponIssueOutboxPort outboxPort, Clock clock) {
        this.openCampaigns = openCampaigns;
        this.soldOutFlags = soldOutFlags;
        this.gate = gate;
        this.outboxPort = outboxPort;
        this.clock = clock;
    }

    /**
     * 열린 캠페인마다 입장 Lua를 한 번 부른다. 열린 캠페인이 없으면 Redis를 부르지 않는다.
     */
    public void tick() {
        openCampaigns.refreshIfStale();
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (CampaignSnapshot campaign : openCampaigns.all()) {
            if (!campaign.isOpenAt(now)) {
                continue;
            }
            try {
                admit(campaign);
            } catch (RuntimeException e) {
                log.warn("입장 실패 — 다음 구간에 다시 시도한다. campaignId={}", campaign.id(), e);
            }
        }
    }

    /** 테스트·tick이 함께 쓰는 한 캠페인 한 구간 */
    public AdmitResult admit(CampaignSnapshot campaign) {
        AdmitResult result = gate.admit(campaign.id(), campaign.totalQuantity());
        switch (result.status()) {
            case UNKNOWN -> recover(campaign.id());
            case SOLD_OUT -> soldOutFlags.markSoldOut(campaign.id());
            case OK -> { }
        }
        return result;
    }

    /**
     * 상태 키가 없다: outbox(= 지금까지의 당첨자 전부)로 Set을 다시 채우고 연다. 복구 중에는 모든 Lua가 UNKNOWN이라 새 당첨이 없다.
     * 복구 잠금을 얻어 복구를 시도했으면 true (Lua가 상태 키를 이미 본 경우 아무것도 바꾸지 않는다).
     */
    /**
     * 요청 경로용: 복구가 실패해도(Redis·DB 장애) 예외를 올리지 않고 false — 호출한 쪽은 그대로 503으로 답한다.
     */
    public boolean tryRecover(Long campaignId) {
        try {
            return recover(campaignId);
        } catch (RuntimeException e) {
            log.warn("요청 중 복구 실패 — 503으로 답하고 다음 요청·스케줄러가 다시 한다. campaignId={}", campaignId, e);
            return false;
        }
    }

    public boolean recover(Long campaignId) {
        if (!gate.tryLockRebuild(campaignId)) {
            return false;
        }
        Set<Long> winners = outboxPort.findUserIds(campaignId);
        gate.rebuild(campaignId, winners);
        log.warn("[COUPON_GATE_REBUILT] 상태 키가 없어 outbox 기준으로 당첨자 Set을 다시 채웠습니다. campaignId={}, winners={}",
            campaignId, winners.size());
        return true;
    }
}
