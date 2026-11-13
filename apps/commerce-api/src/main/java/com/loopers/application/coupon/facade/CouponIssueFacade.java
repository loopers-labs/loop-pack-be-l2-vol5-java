package com.loopers.application.coupon.facade;

import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.coupon.gateway.CouponIssueRequestSender;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.vo.IssueAcceptance;
import com.loopers.domain.coupon.vo.IssueRequested;
import com.loopers.domain.coupon.vo.IssueSnapshot;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * 접수: Lua 1(순번) → Kafka 확인 → Lua 2(확정) → 응답 (8주차 D-133).
 * 판정 전에 DB 를 거치지 않고, 표식이 없을 때만 DB 로 404 · 409 · 503 을 가린다
 */
@RequiredArgsConstructor
@Component
public class CouponIssueFacade {

    private final CouponIssueQueue couponIssueQueue;
    private final CouponIssueRequestSender couponIssueRequestSender;
    private final CouponCampaignRepository couponCampaignRepository;
    private final Clock clock;

    public CouponIssueInfo request(Long campaignId, Long userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        IssueAcceptance acceptance = onQueue(campaignId,
            () -> couponIssueQueue.accept(campaignId, userId, now.toInstant().toEpochMilli()));
        return switch (acceptance) {
            case IssueAcceptance.Pending pending -> sendAndConfirm(campaignId, userId, pending.sequence(), now);
            case IssueAcceptance.Existing existing -> existing.snapshot().state() == IssueState.PENDING_CONFIRM
                // 503 뒤 재요청이면 같은 key 로 다시 보내 확인한다 (D-134)
                ? sendAndConfirm(campaignId, userId, existing.snapshot().sequence(), now)
                : CouponIssueInfo.from(campaignId, existing.snapshot());
            case IssueAcceptance.NotActive notActive -> throw new CoreException(CouponErrorCode.CAMPAIGN_NOT_ACTIVE);
            case IssueAcceptance.SoldOut soldOut -> CouponIssueInfo.soldOut(campaignId);
        };
    }

    /** broker 확인이 없으면 확정하지 않는다. PENDING_CONFIRM 은 재전송 · 대사가 정리한다 (D-133, D-134) */
    private CouponIssueInfo sendAndConfirm(Long campaignId, Long userId, long sequence, ZonedDateTime now) {
        if (!couponIssueRequestSender.send(new IssueRequested(campaignId, userId, sequence, now))) {
            throw new CoreException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }
        IssueSnapshot snapshot = onQueue(campaignId, () -> couponIssueQueue.confirm(campaignId, userId))
            .orElseThrow(() -> new IllegalStateException(
                "확정할 접수 기록이 없음. campaignId=" + campaignId + " userId=" + userId));
        return CouponIssueInfo.from(campaignId, snapshot);
    }

    /** 저장소 접근 실패 · timeout 은 결과를 모르므로 503. 표식이 없으면 DB 로 원인을 가린다 */
    private <T> T onQueue(Long campaignId, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw new CoreException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        } catch (CoreException e) {
            if (e.getErrorCode() == CouponErrorCode.COUPON_ISSUE_UNAVAILABLE) {
                throw unavailableReason(campaignId);
            }
            throw e;
        }
    }

    private CoreException unavailableReason(Long campaignId) {
        CouponCampaign campaign = couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(CouponErrorCode.CAMPAIGN_NOT_FOUND));
        if (!campaign.isOpen()) {
            return new CoreException(CouponErrorCode.CAMPAIGN_NOT_ACTIVE);
        }
        return new CoreException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
    }
}
