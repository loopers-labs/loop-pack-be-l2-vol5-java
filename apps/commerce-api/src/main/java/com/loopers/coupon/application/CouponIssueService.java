package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponIssueUseCase;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponGatePort.GateUnavailableException;
import com.loopers.coupon.application.port.out.CouponGatePort.IssueDecision;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.application.port.out.UserCouponPort;
import com.loopers.coupon.domain.CampaignSnapshot;
import com.loopers.coupon.domain.UserCoupon;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 발급 (D-2 판정 순서, D-4-4 입장 키). API는 쿠폰 업무 표(user_coupon·coupon_campaign)에 쓰지 않고 당첨 기록(outbox)만 남긴다.
 * <ol>
 *   <li>열린 캠페인인가(404·409) → 서버 메모리 품절 표시(409)</li>
 *   <li>Redis Lua 한 번: 상태 키 없음 503 · 이미 당첨 · 입장 전 409 · 품절 409 · 당첨(Set에 추가)</li>
 *   <li>당첨·이미 당첨이면 기록을 남긴다(없을 때만). 실패하면 Set에서 빼지 않고 503 — 다시 요청하면 이미 당첨에서 기록을 채운다 (앞으로 채우기)</li>
 *   <li>기록이 남은 뒤 입장 키를 지운다. 지우지 못해도 202 — 키는 TTL로 사라지고, 재요청은 이미 당첨에서 걸린다</li>
 * </ol>
 * 기록은 한 문장(INSERT)이라 그 자체가 짧은 트랜잭션이다. 메서드 전체를 트랜잭션으로 감싸지 않는다 — Redis 호출 동안 DB 커넥션을 쥐지 않게.
 */
@Slf4j
@Service
public class CouponIssueService implements CouponIssueUseCase {

    private final OpenCampaigns openCampaigns;
    private final SoldOutFlags soldOutFlags;
    private final CouponGatePort gate;
    private final CouponIssueOutboxPort outboxPort;
    private final UserCouponPort userCouponPort;
    private final Clock clock;

    public CouponIssueService(OpenCampaigns openCampaigns, SoldOutFlags soldOutFlags, CouponGatePort gate,
                              CouponIssueOutboxPort outboxPort, UserCouponPort userCouponPort, Clock clock) {
        this.openCampaigns = openCampaigns;
        this.soldOutFlags = soldOutFlags;
        this.gate = gate;
        this.outboxPort = outboxPort;
        this.userCouponPort = userCouponPort;
        this.clock = clock;
    }

    @Override
    public IssueOutcome issue(Long campaignId, Long userId) {
        CampaignSnapshot campaign = openCampaigns.require(campaignId);
        if (soldOutFlags.isSoldOut(campaignId)) {
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        }
        IssueDecision decision = decide(campaign, userId);
        return switch (decision) {
            case UNKNOWN -> throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "발급 상태를 확인하는 중입니다. 잠시 후 다시 시도해 주세요.");
            case NOT_ADMITTED -> throw new CoreException(ErrorType.QUEUE_NOT_ADMITTED);
            case SOLD_OUT -> {
                soldOutFlags.markSoldOut(campaignId);
                throw new CoreException(ErrorType.COUPON_SOLD_OUT);
            }
            case ALREADY -> alreadyWon(campaignId, userId);
            case ACCEPTED -> {
                record(campaignId, userId);
                deleteEntryQuietly(campaignId, userId);
                yield IssueOutcome.accepted();
            }
        };
    }

    private IssueDecision decide(CampaignSnapshot campaign, Long userId) {
        try {
            return gate.issue(campaign.id(), userId, campaign.totalQuantity());
        } catch (GateUnavailableException e) {
            log.warn("발급 판정 실패 — Redis에 닿지 못해 우회하지 않고 503. campaignId={}, userId={}", campaign.id(), userId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 이미 당첨된 사람: 쿠폰이 이미 있으면 200 + 쿠폰 id들, 아직이면 기록을 채워 보고 202.
     */
    private IssueOutcome alreadyWon(Long campaignId, Long userId) {
        List<Long> couponIds = userCouponPort.findByCampaignIdAndUserId(campaignId, userId).stream().map(UserCoupon::getId).toList();
        if (!couponIds.isEmpty()) {
            return IssueOutcome.issued(couponIds);
        }
        record(campaignId, userId);
        deleteEntryQuietly(campaignId, userId);
        return IssueOutcome.accepted();
    }

    private void record(Long campaignId, Long userId) {
        try {
            outboxPort.appendIfAbsent(campaignId, userId, ZonedDateTime.now(clock));
        } catch (DataAccessException e) {
            // 당첨자 Set에서 빼지 않는다 — 다시 요청하면 ALREADY에서, 아니면 맞춰 보기가 기록을 채운다 (D-2)
            log.warn("당첨 기록 실패 — Set에 남긴 채 503. campaignId={}, userId={}", campaignId, userId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }

    private void deleteEntryQuietly(Long campaignId, Long userId) {
        try {
            gate.deleteEntry(campaignId, userId);
        } catch (RuntimeException e) {
            log.warn("입장 키 삭제 실패 — TTL로 사라진다. campaignId={}, userId={}", campaignId, userId, e);
        }
    }
}
