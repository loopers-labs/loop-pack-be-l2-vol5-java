package com.loopers.domain.coupon;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponCampaignRepository campaignRepository;
    private final CouponRepository couponRepository;

    public CouponCampaign open(CampaignName name, DiscountAmount discountAmount, IssueQuota totalQuota, boolean active) {
        return campaignRepository.save(CouponCampaign.open(name, discountAmount, totalQuota, active));
    }

    public Coupon issue(Long campaignId, Long userId, int issueNumber, Instant issuedAt) {
        CouponCampaign campaign = campaignRepository.findById(campaignId)
            .orElseThrow(() -> new DomainException(DomainError.COUPON_CAMPAIGN_NOT_FOUND));
        Coupon coupon = couponRepository.insert(campaign.issueTo(userId, issueNumber, issuedAt));
        if (!campaignRepository.increaseIssuedCountWithinQuota(campaignId, 1)) {
            throw new DomainException(DomainError.COUPON_SOLD_OUT);
        }
        return coupon;
    }

    public List<Coupon> issueAll(Long campaignId, List<CouponIssuePublisher.CouponIssue> issues) {
        CouponCampaign campaign = campaignRepository.findById(campaignId)
            .orElseThrow(() -> new DomainException(DomainError.COUPON_CAMPAIGN_NOT_FOUND));
        List<Coupon> coupons = couponRepository.insertAll(issues.stream()
            .map(issue -> campaign.issueTo(issue.userId(), issue.issueNumber(), issue.reservedAt()))
            .toList());
        if (!campaignRepository.increaseIssuedCountWithinQuota(campaignId, issues.size())) {
            throw new DomainException(DomainError.COUPON_SOLD_OUT);
        }
        return coupons;
    }

    public Optional<CouponCampaign> findCampaign(Long campaignId) {
        return campaignRepository.findById(campaignId);
    }

    public List<Long> findActiveCampaignIds() {
        return campaignRepository.findActiveIds();
    }

    public Optional<Coupon> findIssued(Long campaignId, Long userId) {
        return couponRepository.find(campaignId, userId);
    }

    public List<Coupon> findOwnedBy(Long userId) {
        return couponRepository.findAllOwnedBy(userId);
    }

    public int maxIssueNumber(Long campaignId) {
        return couponRepository.maxIssueNumber(campaignId);
    }

    public Set<Integer> issuedNumbersBetween(Long campaignId, int from, int to) {
        return couponRepository.issuedNumbersBetween(campaignId, from, to);
    }

    public List<CouponReservation.Reservation> issuedAfter(Long campaignId, int afterNumber, int limit) {
        return couponRepository.findIssuedAfter(campaignId, afterNumber, limit);
    }
}
