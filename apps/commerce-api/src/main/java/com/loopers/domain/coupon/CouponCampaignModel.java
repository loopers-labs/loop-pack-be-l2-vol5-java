package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "coupon_campaign")
public class CouponCampaignModel extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "benefit_description", nullable = false, length = 1000)
    private String benefitDescription;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    /** 이 시각부터 신규 발급을 받지 않는다. 발급 판단용 Redis 키도 이 시각 뒤 일정 시간이 지나면 사라진다. */
    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    protected CouponCampaignModel() {}

    public CouponCampaignModel(String name, String benefitDescription, boolean active, int totalQuota, Instant endAt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("캠페인 이름은 비어있을 수 없습니다.");
        }
        String normalizedName = name.strip();
        if (normalizedName.codePointCount(0, normalizedName.length()) > 100) {
            throw new IllegalArgumentException("캠페인 이름은 100자 이하여야 합니다.");
        }
        if (benefitDescription == null || benefitDescription.isBlank()) {
            throw new IllegalArgumentException("쿠폰 혜택 설명은 비어있을 수 없습니다.");
        }
        if (benefitDescription.codePointCount(0, benefitDescription.length()) > 1000) {
            throw new IllegalArgumentException("쿠폰 혜택 설명은 1,000자 이하여야 합니다.");
        }
        if (totalQuota < 0) {
            throw new IllegalArgumentException("총 발급 한도는 0 이상이어야 합니다.");
        }
        if (endAt == null) {
            throw new IllegalArgumentException("행사 종료 시각은 필수입니다.");
        }
        this.name = normalizedName;
        this.benefitDescription = benefitDescription;
        this.active = active;
        this.totalQuota = totalQuota;
        this.issuedCount = 0;
        this.endAt = endAt;
    }
}
