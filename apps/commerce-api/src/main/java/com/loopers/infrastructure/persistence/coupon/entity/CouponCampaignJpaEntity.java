package com.loopers.infrastructure.persistence.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "coupon_campaigns")
@Check(constraints = "total_quota > 0 AND issued_count >= 0 AND issued_count <= total_quota"
    + " AND discount_amount > 0 AND starts_at < ends_at")
public class CouponCampaignJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100, updatable = false)
    private String name;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "total_quota", nullable = false, updatable = false)
    private int totalQuota;
    @Column(name = "issued_count", nullable = false)
    private int issuedCount;
    @Column(name = "benefit_description", nullable = false, length = 500, updatable = false)
    private String benefitDescription;
    @Column(name = "discount_amount", nullable = false, updatable = false)
    private long discountAmount;
    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;
    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CouponCampaignJpaEntity() {}

    CouponCampaignJpaEntity(String name, int totalQuota, String benefitDescription, long discountAmount,
                            Instant startsAt, Instant endsAt, boolean active, int issuedCount) {
        this.name = name;
        this.totalQuota = totalQuota;
        this.benefitDescription = benefitDescription;
        this.discountAmount = discountAmount;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        apply(active, issuedCount);
    }

    void apply(boolean active, int issuedCount) {
        this.active = active;
        this.issuedCount = issuedCount;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public int getTotalQuota() { return totalQuota; }
    public int getIssuedCount() { return issuedCount; }
    public String getBenefitDescription() { return benefitDescription; }
    public long getDiscountAmount() { return discountAmount; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
}
