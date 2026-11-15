package com.loopers.infrastructure.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.coupon.CouponCampaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;

@Getter
@Entity
@Table(name = "coupon_campaigns")
public class CouponCampaignJpaEntity extends BaseEntity {
    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private long totalQuota;

    @Column(nullable = false)
    private long issuedCount;

    @Column(nullable = false, length = 255)
    private String benefitDescription;

    protected CouponCampaignJpaEntity() {
    }

    public CouponCampaignJpaEntity(String name, boolean active, long totalQuota, String benefitDescription) {
        if (name == null || name.isBlank() || totalQuota < 0
                || benefitDescription == null || benefitDescription.isBlank()) {
            throw new IllegalArgumentException("캠페인의 이름, 한도, 혜택 정보를 확인해 주세요.");
        }
        this.name = name;
        this.active = active;
        this.totalQuota = totalQuota;
        this.benefitDescription = benefitDescription;
    }

    public CouponCampaign toDomain() {
        return new CouponCampaign(getId(), name, active, totalQuota, issuedCount, benefitDescription);
    }

    public void increaseIssuedCount() {
        if (!active || issuedCount >= totalQuota) {
            throw new IllegalStateException("발급할 수 없는 캠페인의 발급 수를 증가시킬 수 없습니다.");
        }
        issuedCount++;
    }
}
