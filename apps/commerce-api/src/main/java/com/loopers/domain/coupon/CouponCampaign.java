package com.loopers.domain.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "coupon_campaign")
public class CouponCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "benefit_description", nullable = false, length = 255)
    private String benefitDescription;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "waiting_count", nullable = false)
    private int waitingCount;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    protected CouponCampaign() {
    }

    public void reserveOne() {
        if (!active) {
            throw new CouponException(CouponException.Reason.INACTIVE);
        }
        if (waitingCount >= totalQuota - issuedCount) {
            throw new CouponException(CouponException.Reason.SOLD_OUT);
        }
        waitingCount++;
    }

    public void issueOne() {
        if (waitingCount == 0) {
            throw new IllegalStateException("No reserved coupon to issue");
        }
        waitingCount--;
        issuedCount++;
    }

    public Long getId() {
        return id;
    }

    public boolean isActive() {
        return active;
    }

    public int getTotalQuota() {
        return totalQuota;
    }

    public int allocatedCount() {
        return Math.addExact(waitingCount, issuedCount);
    }

    public String getBenefitDescription() {
        return benefitDescription;
    }
}
