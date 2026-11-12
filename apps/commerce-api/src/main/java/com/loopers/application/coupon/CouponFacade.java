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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CouponFacade {

    private final CouponService couponService;
    private final CampaignCache campaignCache;
    private final CouponReservation couponReservation;
    private final CouponIssuePublisher couponIssuePublisher;
    private final CouponPublishSlots couponPublishSlots;

    public sealed interface IssueResult {

        int issueNumber();

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
        CouponCampaign campaign = campaignCache.get(campaignId);
        if (!campaign.isActive()) {
            throw new DomainException(DomainError.COUPON_CAMPAIGN_INACTIVE);
        }
        if (!couponPublishSlots.tryAcquire()) {
            throw new CoreException(ErrorType.COUPON_ISSUE_BUSY);
        }
        try {
            CouponReservation.Result reserved = couponReservation.reserve(campaignId, userId,
                campaign.getTotalQuota(), campaign.getIssuedCount());
            return switch (reserved) {
                case CouponReservation.Result.Reserved accepted ->
                    publish(campaignId, userId, accepted.issueNumber(), now);
                case CouponReservation.Result.Existing existing -> couponService.findIssued(campaignId, userId)
                    .<IssueResult>map(coupon -> new IssueResult.Issued(existing.issueNumber(), coupon))
                    .orElseGet(() -> new IssueResult.Pending(existing.issueNumber()));
                case CouponReservation.Result.SoldOut soldOut -> throw new DomainException(DomainError.COUPON_SOLD_OUT);
                case CouponReservation.Result.Lost lost -> throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            };
        } finally {
            couponPublishSlots.release();
        }
    }

    private IssueResult publish(Long campaignId, Long userId, int issueNumber, Instant reservedAt) {
        CouponIssuePublisher.Outcome outcome = couponIssuePublisher.publish(
            new CouponIssuePublisher.CouponIssue(campaignId, userId, issueNumber, reservedAt));
        return switch (outcome) {
            case CouponIssuePublisher.Outcome.Published published -> new IssueResult.Accepted(issueNumber);
            case CouponIssuePublisher.Outcome.Unconfirmed unconfirmed -> {
                couponReservation.cancel(campaignId, userId);
                throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            }
        };
    }

    public List<Coupon> findMine(Long userId) {
        return couponService.findOwnedBy(userId);
    }
}
