package com.loopers.domain.coupon;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;

import java.time.Instant;

public class CouponCampaign {

    private final Long id;
    private final CampaignName name;
    private final DiscountAmount discountAmount;
    private final IssueQuota totalQuota;
    private final int issuedCount;
    private final boolean active;

    private CouponCampaign(Long id, CampaignName name, DiscountAmount discountAmount,
                           IssueQuota totalQuota, int issuedCount, boolean active) {
        this.id = id;
        this.name = name;
        this.discountAmount = discountAmount;
        this.totalQuota = totalQuota;
        this.issuedCount = issuedCount;
        this.active = active;
    }

    public static CouponCampaign open(CampaignName name, DiscountAmount discountAmount,
                                      IssueQuota totalQuota, boolean active) {
        return new CouponCampaign(null, name, discountAmount, totalQuota, 0, active);
    }

    public static CouponCampaign restore(Long id, CampaignName name, DiscountAmount discountAmount,
                                         IssueQuota totalQuota, int issuedCount, boolean active) {
        return new CouponCampaign(id, name, discountAmount, totalQuota, issuedCount, active);
    }

    public Coupon issueTo(Long userId, Instant issuedAt) {
        if (!active) {
            throw new DomainException(DomainError.COUPON_CAMPAIGN_INACTIVE);
        }
        return new Coupon(null, id, userId, discountAmount, issuedAt);
    }

    public Long getId() {
        return id;
    }

    public CampaignName getName() {
        return name;
    }

    public DiscountAmount getDiscountAmount() {
        return discountAmount;
    }

    public IssueQuota getTotalQuota() {
        return totalQuota;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public boolean isActive() {
        return active;
    }
}
