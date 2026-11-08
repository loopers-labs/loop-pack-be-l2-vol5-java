package com.loopers.infrastructure.coupon;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "coupon_campaign")
class CouponCampaignEntity extends AuditEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "discount_amount", nullable = false, updatable = false)
    private long discountAmount;

    @Column(name = "total_quota", nullable = false, updatable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected CouponCampaignEntity() {}

    static CouponCampaignEntity from(CouponCampaign campaign) {
        CouponCampaignEntity entity = new CouponCampaignEntity();
        entity.name = campaign.getName().value();
        entity.discountAmount = campaign.getDiscountAmount().value();
        entity.totalQuota = campaign.getTotalQuota().value();
        entity.issuedCount = campaign.getIssuedCount();
        entity.active = campaign.isActive();
        return entity;
    }

    CouponCampaign toDomain() {
        return CouponCampaign.restore(getId(), CampaignName.of(name), DiscountAmount.of(discountAmount),
            IssueQuota.of(totalQuota), issuedCount, active);
    }
}
