package com.loopers.infrastructure.coupon;

import com.loopers.domain.AuditEntity;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.DiscountAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
    name = "coupon",
    uniqueConstraints = @UniqueConstraint(name = CouponEntity.UK_CAMPAIGN_USER, columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupon_user_issued", columnList = "user_id, issued_at, id")
)
class CouponEntity extends AuditEntity {

    static final String UK_CAMPAIGN_USER = "uk_coupon_campaign_user";

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "issue_number", nullable = false, updatable = false)
    private int issueNumber;

    @Column(name = "discount_amount", nullable = false, updatable = false)
    private long discountAmount;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    protected CouponEntity() {}

    static CouponEntity from(Coupon coupon) {
        CouponEntity entity = new CouponEntity();
        entity.campaignId = coupon.campaignId();
        entity.userId = coupon.userId();
        entity.issueNumber = coupon.issueNumber();
        entity.discountAmount = coupon.discountAmount().value();
        entity.issuedAt = coupon.issuedAt();
        return entity;
    }

    Coupon toDomain() {
        return new Coupon(getId(), campaignId, userId, issueNumber, DiscountAmount.of(discountAmount), issuedAt);
    }
}
