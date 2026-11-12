package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponQueueUseCase;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponGatePort.GateUnavailableException;
import com.loopers.coupon.application.port.out.CouponGatePort.QueueState;
import com.loopers.coupon.domain.CampaignSnapshot;
import com.loopers.coupon.domain.PollInterval;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 대기열 등록·조회 (D-4). 판정은 Redis Lua 한 번이고, 품절이 켜진 서버는 Redis까지 가지 않는다 (D-4-6).
 */
@Slf4j
@Service
public class CouponQueueService implements CouponQueueUseCase {

    private final OpenCampaigns openCampaigns;
    private final SoldOutFlags soldOutFlags;
    private final CouponGatePort gate;
    private final int admitPerSecond;

    public CouponQueueService(
        OpenCampaigns openCampaigns,
        SoldOutFlags soldOutFlags,
        CouponGatePort gate,
        @Value("${coupon.queue.admission.per-tick}") int perTick,
        @Value("${coupon.queue.admission.tick}") Duration tick
    ) {
        this.openCampaigns = openCampaigns;
        this.soldOutFlags = soldOutFlags;
        this.gate = gate;
        this.admitPerSecond = (int) (perTick * 1_000L / tick.toMillis());
    }

    @Override
    public QueueStatusInfo register(Long campaignId, Long userId) {
        CampaignSnapshot campaign = openCampaigns.require(campaignId);
        if (soldOutFlags.isSoldOut(campaignId)) {
            return QueueStatusInfo.of(QueueStatus.SOLD_OUT);
        }
        return toInfo(campaignId, call(() -> gate.register(campaignId, userId, campaign.totalQuantity(), campaign.queueCapacity())));
    }

    @Override
    public QueueStatusInfo status(Long campaignId, Long userId) {
        CampaignSnapshot campaign = openCampaigns.require(campaignId);
        if (soldOutFlags.isSoldOut(campaignId)) {
            return QueueStatusInfo.of(QueueStatus.SOLD_OUT);
        }
        return toInfo(campaignId, call(() -> gate.status(campaignId, userId, campaign.totalQuantity())));
    }

    private QueueStatusInfo toInfo(Long campaignId, QueueState state) {
        return switch (state.type()) {
            case UNKNOWN -> throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "대기열 상태를 확인하는 중입니다. 잠시 후 다시 시도해 주세요.");
            case QUEUE_FULL -> throw new CoreException(ErrorType.TOO_MANY_REQUESTS, "대기 인원이 많습니다. 잠시 후 다시 시도해 주세요.");
            case SOLD_OUT -> {
                soldOutFlags.markSoldOut(campaignId);
                yield QueueStatusInfo.of(QueueStatus.SOLD_OUT);
            }
            case WAITING -> new QueueStatusInfo(QueueStatus.WAITING, state.ahead(),
                PollInterval.of(state.ahead(), admitPerSecond, ThreadLocalRandom.current()));
            case ADMITTED -> QueueStatusInfo.of(QueueStatus.ADMITTED);
            case ISSUED -> QueueStatusInfo.of(QueueStatus.ISSUED);
            case NOT_IN_QUEUE -> QueueStatusInfo.of(QueueStatus.NOT_IN_QUEUE);
        };
    }

    private static QueueState call(Supplier<QueueState> command) {
        try {
            return command.get();
        } catch (GateUnavailableException e) {
            log.warn("대기열 판정 실패 — Redis에 닿지 못해 503", e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }
}
