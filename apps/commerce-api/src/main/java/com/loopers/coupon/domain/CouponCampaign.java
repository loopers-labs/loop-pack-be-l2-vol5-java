package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "coupon_campaign")
@Check(
    name = "ck_coupon_campaign_count_within_quota",
    constraints = "total_quota >= 0 and issued_count >= 0 and issued_count <= total_quota"
)
public class CouponCampaign extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String benefit;

    @Column(nullable = false)
    private boolean issuable;

    @Column(name = "total_quota", nullable = false)
    private long totalQuota;

    @Column(name = "issued_count", nullable = false)
    private long issuedCount;

    protected CouponCampaign() {
    }

    public CouponCampaign(String name, String benefit, boolean issuable, long totalQuota) {
        this.name = name;
        this.benefit = benefit;
        this.issuable = issuable;
        this.totalQuota = totalQuota;
        this.issuedCount = 0;
    }

    public void issue() {
        if (!issuable) {
            throw new CoreException(ErrorCode.COUPON_CAMPAIGN_INACTIVE);
        }
        if (issuedCount >= totalQuota) {
            throw new CoreException(ErrorCode.COUPON_CAMPAIGN_SOLD_OUT);
        }
        issuedCount++;
    }

    public void stopIssuance() {
        issuable = false;
    }

    public Long getId() {
        return super.getId();
    }

    public String getName() {
        return name;
    }

    public String getBenefit() {
        return benefit;
    }

    public boolean isIssuable() {
        return issuable;
    }

    public long getTotalQuota() {
        return totalQuota;
    }

    public long getIssuedCount() {
        return issuedCount;
    }
}
