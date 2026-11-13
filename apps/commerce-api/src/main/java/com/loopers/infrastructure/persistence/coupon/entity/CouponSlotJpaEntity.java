package com.loopers.infrastructure.persistence.coupon.entity;

import com.loopers.domain.coupon.model.CouponSlotState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "coupon_slots",
    indexes = @Index(name = "idx_coupon_slots_campaign_state_id", columnList = "campaign_id,state,id"),
    uniqueConstraints = @UniqueConstraint(name = "uk_coupon_slots_id_campaign", columnNames = {"id", "campaign_id"}))
@Check(constraints = "state IN ('AVAILABLE', 'ISSUED')")
public class CouponSlotJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private long campaignId;
    /** DDL의 FK만 표현한다. 쓰기/조회는 campaignId로 하며 부모를 fetch/cascade하지 않는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", insertable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_coupon_slots_campaign"))
    private CouponCampaignJpaEntity campaign;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private CouponSlotState state;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CouponSlotJpaEntity() {}

    CouponSlotJpaEntity(long campaignId, CouponSlotState state) {
        this.campaignId = campaignId;
        this.state = state;
    }

    void apply(CouponSlotState state) {
        this.state = state;
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
    public long getCampaignId() { return campaignId; }
    public CouponSlotState getState() { return state; }
}
