package com.loopers.infrastructure.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coupon_campaigns")
public class CouponCampaignJpaEntity extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "total_quota", nullable = false)
    private long totalQuota;

    @Column(name = "issued_count", nullable = false)
    private long issuedCount;

    @Column(name = "benefit_description", nullable = false, columnDefinition = "text")
    private String benefitDescription;

    @Column(name = "start_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant startAt;

    @Column(name = "end_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant endAt;

    @Column(name = "terminated_at", columnDefinition = "datetime(6)")
    private Instant terminatedAt;

    private CouponCampaignJpaEntity(String name, long totalQuota, String benefitDescription,
                                    Instant startAt, Instant endAt) {
        this.name = name;
        this.totalQuota = totalQuota;
        this.benefitDescription = benefitDescription;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public static CouponCampaignJpaEntity create(String name, long totalQuota, String benefitDescription,
                                                 Instant startAt, Instant endAt) {
        return new CouponCampaignJpaEntity(name, totalQuota, benefitDescription, startAt, endAt);
    }
}
