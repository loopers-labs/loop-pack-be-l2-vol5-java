package com.loopers.domains.coupon.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "issued_coupon",
    uniqueConstraints = @UniqueConstraint(name = IssuedCouponJpaEntity.UNIQUE_CAMPAIGN_USER, columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_issued_coupon_user_issued", columnList = "user_id, issued_at, id"))
public class IssuedCouponJpaEntity {
    static final String UNIQUE_CAMPAIGN_USER = "uk_issued_coupon_campaign_user";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long campaignId;

    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private long discountAmount;

    @Column(nullable = false)
    private Instant issuedAt;

    protected IssuedCouponJpaEntity() {
    }

    IssuedCouponJpaEntity(long campaignId, long userId, long discountAmount, Instant issuedAt) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.discountAmount = discountAmount;
        this.issuedAt = issuedAt;
    }

    long id() {
        return id;
    }

    long campaignId() {
        return campaignId;
    }

    long userId() {
        return userId;
    }

    long discountAmount() {
        return discountAmount;
    }

    Instant issuedAt() {
        return issuedAt;
    }
}
