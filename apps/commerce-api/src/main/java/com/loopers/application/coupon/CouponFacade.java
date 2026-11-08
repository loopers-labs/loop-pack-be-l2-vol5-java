package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponAdmission;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
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
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CouponFacade {

    private final CouponService couponService;
    private final CouponTransactions couponTransactions;
    private final CouponAdmission couponAdmission;

    public record Issued(Coupon coupon, boolean newlyIssued) {
    }

    public CouponCampaign openCampaign(CampaignName name, DiscountAmount discountAmount, IssueQuota totalQuota,
                                       boolean active) {
        return couponService.open(name, discountAmount, totalQuota, active);
    }

    public Issued issue(Long campaignId, Long userId, Instant now) {
        Optional<Coupon> existing = couponService.findIssued(campaignId, userId);
        if (existing.isPresent()) {
            return new Issued(existing.get(), false);
        }
        String token = switch (couponAdmission.tryAcquire()) {
            case CouponAdmission.Result.Admitted admitted -> admitted.token();
            case CouponAdmission.Result.Full full -> throw new CoreException(ErrorType.COUPON_BUSY);
            case CouponAdmission.Result.Unavailable unavailable ->
                throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        };
        try {
            return issueNew(campaignId, userId, now);
        } finally {
            couponAdmission.release(token);
        }
    }

    private Issued issueNew(Long campaignId, Long userId, Instant now) {
        try {
            return new Issued(couponTransactions.issue(campaignId, userId, now), true);
        } catch (DomainException e) {
            if (e.error() != DomainError.COUPON_ALREADY_ISSUED) {
                throw e;
            }
            Coupon issued = couponService.findIssued(campaignId, userId)
                .orElseThrow(() -> new IllegalStateException(
                    "유일 제약에 걸렸는데 기존 쿠폰이 없습니다: campaignId=" + campaignId + ", userId=" + userId));
            return new Issued(issued, false);
        }
    }

    public List<Coupon> findMine(Long userId) {
        return couponService.findOwnedBy(userId);
    }
}
