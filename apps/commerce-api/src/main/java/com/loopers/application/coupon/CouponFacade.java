package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponPublishSlots;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponFacade {

    private final CouponService couponService;
    private final CampaignCache campaignCache;
    private final CouponReservation couponReservation;
    private final CouponIssuePublisher couponIssuePublisher;
    private final CouponPublishSlots couponPublishSlots;
    private final CouponLossRecovery couponLossRecovery;

    public sealed interface IssueResult {

        record Accepted(int issueNumber) implements IssueResult {
        }

        record Pending(int issueNumber) implements IssueResult {
        }

        record Issued(int issueNumber, Coupon coupon) implements IssueResult {
        }
    }

    public CouponCampaign openCampaign(CampaignName name, DiscountAmount discountAmount, IssueQuota totalQuota,
                                       boolean active) {
        return couponService.open(name, discountAmount, totalQuota, active);
    }

    public IssueResult issue(Long campaignId, Long userId, Instant now) {
        try {
            CouponCampaign campaign = campaignCache.get(campaignId);
            if (!campaign.isActive()) {
                throw new DomainException(DomainError.COUPON_CAMPAIGN_INACTIVE);
            }
            CouponReservation.Result reserved = reserveWithinSlot(campaignId, userId, campaign, now);
            return switch (reserved) {
                case CouponReservation.Result.Reserved(int issueNumber) -> new IssueResult.Accepted(issueNumber);
                case CouponReservation.Result.Existing(int issueNumber) -> couponService.findIssued(campaignId, userId)
                    .<IssueResult>map(coupon -> new IssueResult.Issued(issueNumber, coupon))
                    .orElseGet(() -> new IssueResult.Pending(issueNumber));
                case CouponReservation.Result.SoldOut() -> throw new DomainException(DomainError.COUPON_SOLD_OUT);
                case CouponReservation.Result.Lost() -> {
                    couponLossRecovery.start(campaignId);
                    throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
                }
                case CouponReservation.Result.Closed() -> throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            };
        } catch (DataAccessException e) {
            log.warn("저장소가 답하지 않아 접수하지 않는다. 예약이 늦게 적용됐으면 재요청이 같은 번호를 받고 대사가 메운다: campaignId={}, userId={}, cause={}",
                campaignId, userId, NestedExceptionUtils.getMostSpecificCause(e).toString());
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    private CouponReservation.Result reserveWithinSlot(Long campaignId, Long userId, CouponCampaign campaign,
                                                       Instant now) {
        if (!couponPublishSlots.tryAcquire()) {
            throw new CoreException(ErrorType.COUPON_ISSUE_BUSY);
        }
        try {
            CouponReservation.Result reserved = couponReservation.reserve(campaignId, userId,
                campaign.getTotalQuota(), campaign.getIssuedCount());
            if (reserved instanceof CouponReservation.Result.Reserved(int issueNumber)) {
                publish(campaignId, userId, issueNumber, now);
            }
            return reserved;
        } finally {
            couponPublishSlots.release();
        }
    }

    private void publish(Long campaignId, Long userId, int issueNumber, Instant reservedAt) {
        CouponIssuePublisher.Outcome outcome = couponIssuePublisher.publish(
            new CouponIssuePublisher.CouponIssue(campaignId, userId, issueNumber, reservedAt));
        switch (outcome) {
            case CouponIssuePublisher.Outcome.Published() -> {
            }
            case CouponIssuePublisher.Outcome.Unconfirmed(String error) ->
                throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            case CouponIssuePublisher.Outcome.Rejected(String error) -> {
                couponReservation.cancel(campaignId, userId);
                throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            }
        }
    }

    public List<Coupon> findMine(Long userId) {
        return couponService.findOwnedBy(userId);
    }

    public void startRecovery(Long campaignId) {
        couponService.findCampaign(campaignId)
            .orElseThrow(() -> new DomainException(DomainError.COUPON_CAMPAIGN_NOT_FOUND));
        couponLossRecovery.start(campaignId);
    }
}
