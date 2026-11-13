package com.loopers.infrastructure.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coupons",
    uniqueConstraints = @UniqueConstraint(name = "uk_coupon_campaign_user", columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupons_owner_issued", columnList = "user_id, issued_at, id"))
public class CouponJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_coupons_campaign"))
    private CouponCampaignJpaEntity campaign;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "issued_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant issuedAt;

    public static CouponJpaEntity create(CouponCampaignJpaEntity campaign, Long userId, Instant issuedAt) {
        CouponJpaEntity entity = new CouponJpaEntity();
        entity.campaign = campaign;
        entity.userId = userId;
        entity.issuedAt = issuedAt;
        return entity;
    }
}
