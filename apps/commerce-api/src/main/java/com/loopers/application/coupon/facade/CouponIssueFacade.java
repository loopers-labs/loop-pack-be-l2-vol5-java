package com.loopers.application.coupon.facade;

import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.entity.CouponIssueRequest;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.IssueRequestStatus;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.coupon.gateway.CouponIssueRequestSender;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.domain.coupon.repository.CouponIssueRequestRepository;
import com.loopers.domain.coupon.vo.IssueAcceptance;
import com.loopers.domain.coupon.vo.IssueRequested;
import com.loopers.domain.coupon.vo.IssueSnapshot;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;
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
    private final CouponIssueRequestRepository couponIssueRequestRepository;
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

    /**
     * 평소에는 Redis 사본만 읽는다 (D-125). Redis 를 쓸 수 없으면 DB 접수 기록(원본)으로 대신 읽는다 (D-145).
     * 아직 확정되지 않은 접수(PENDING_CONFIRM)는 결과를 모르므로 503 이다
     */
    public CouponIssueInfo find(Long campaignId, Long userId) {
        Optional<IssueSnapshot> snapshot;
        try {
            snapshot = couponIssueQueue.read(campaignId, userId);
        } catch (DataAccessException e) {
            return fromDatabase(campaignId, userId);
        } catch (CoreException e) {
            if (e.getErrorCode() != CouponErrorCode.COUPON_ISSUE_UNAVAILABLE) {
                throw e;
            }
            return fromDatabase(campaignId, userId);
        }
        IssueSnapshot found = snapshot.orElseThrow(() -> new CoreException(CouponErrorCode.COUPON_ISSUE_NOT_FOUND));
        if (found.state() == IssueState.PENDING_CONFIRM) {
            throw new CoreException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }
        return CouponIssueInfo.from(campaignId, found);
    }

    /** 기록이 없으면 "접수 안 함"과 "아직 적재 안 됨"을 구분할 수 없어 404 가 아니라 503 이다 (D-146) */
    private CouponIssueInfo fromDatabase(Long campaignId, Long userId) {
        CouponCampaign campaign = couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(CouponErrorCode.CAMPAIGN_NOT_FOUND));
        CouponIssueRequest request = couponIssueRequestRepository.find(campaignId, userId)
            .orElseThrow(() -> new CoreException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        boolean soldOut = !campaign.isOpen() || campaign.getIssuedCount() >= campaign.getTotalQuota();
        long promoted = request.getStatus() == IssueRequestStatus.RESERVE
            ? couponIssueRequestRepository.countPromoted(campaignId)
            : 0L;
        IssueSnapshot snapshot = IssueSnapshot.of(IssueState.valueOf(request.getStatus().name()), request.getSequence(),
            request.getCouponId(), soldOut, promoted, campaign.getTotalQuota());
        return CouponIssueInfo.from(campaignId, snapshot);
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
