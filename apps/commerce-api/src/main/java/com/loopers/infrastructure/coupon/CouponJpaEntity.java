package com.loopers.infrastructure.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.coupon.Coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(name = "issued_coupons",
        uniqueConstraints = @UniqueConstraint(name = "uk_coupon_campaign_user", columnNames = {"campaign_id", "user_id"}),
        indexes = @Index(name = "idx_coupon_user_issued_id", columnList = "user_id, issued_at, id"))
public class CouponJpaEntity extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private long campaignId;

    @Column(nullable = false, updatable = false)
    private long userId;

    @Column(nullable = false, updatable = false, length = 255)
    private String benefitDescription;

    @Column(nullable = false, updatable = false)
    private ZonedDateTime issuedAt;

    protected CouponJpaEntity() {
    }

    public CouponJpaEntity(long campaignId, long userId, String benefitDescription) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.benefitDescription = benefitDescription;
        this.issuedAt = ZonedDateTime.now();
    }

    public Coupon toDomain() {
        return new Coupon(getId(), campaignId, userId, benefitDescription, issuedAt);
    }
}
