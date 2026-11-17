package com.loopers.infrastructure.persistence.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "coupons", uniqueConstraints = {
    @UniqueConstraint(name = "uk_coupons_campaign_user", columnNames = {"campaign_id", "user_id"}),
    @UniqueConstraint(name = "uk_coupons_slot", columnNames = "slot_id")
}, indexes = @Index(name = "idx_coupons_user_issued_id", columnList = "user_id,issued_at DESC,id DESC"))
@Check(constraints = "user_id > 0 AND discount_amount > 0")
public class CouponJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private long campaignId;
    @Column(name = "slot_id", nullable = false, updatable = false)
    private long slotId;
    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;
    @Column(name = "benefit_description", nullable = false, length = 500, updatable = false)
    private String benefitDescription;
    @Column(name = "discount_amount", nullable = false, updatable = false)
    private long discountAmount;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;
    /** 슬롯 경유 복합 FK만 생성한다. 캠페인으로 직접 FK/cascade를 추가하지 않는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
        @JoinColumn(name = "slot_id", referencedColumnName = "id", insertable = false, updatable = false),
        @JoinColumn(name = "campaign_id", referencedColumnName = "campaign_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_coupons_slot_campaign", foreignKeyDefinition =
        "FOREIGN KEY (slot_id, campaign_id) REFERENCES coupon_slots (id, campaign_id)"))
    private CouponSlotJpaEntity slot;

    protected CouponJpaEntity() {}

    CouponJpaEntity(long campaignId, long slotId, long userId, String benefitDescription, long discountAmount, Instant issuedAt) {
        this.campaignId = campaignId;
        this.slotId = slotId;
        this.userId = userId;
        this.benefitDescription = benefitDescription;
        this.discountAmount = discountAmount;
        this.issuedAt = issuedAt;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public long getCampaignId() { return campaignId; }
    public long getSlotId() { return slotId; }
    public long getUserId() { return userId; }
    public String getBenefitDescription() { return benefitDescription; }
    public long getDiscountAmount() { return discountAmount; }
    public Instant getIssuedAt() { return issuedAt; }
}
