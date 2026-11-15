package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 쿠폰 발급 캠페인. 총 발급 한도(totalQuota)와 현재 발급 수(issuedCount)는 DB가 지키는 원본이다.
 * issuedCount 증가는 이 객체를 고쳐 저장하지 않고 조건부 UPDATE 한 문장으로만 한다(CouponCampaignRepository.increaseIssuedCount).
 */
@Entity
@Table(name = "coupon_campaign")
public class CouponCampaignModel extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "total_quota", nullable = false)
    private int totalQuota;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;

    @Column(name = "benefit_description", nullable = false)
    private String benefitDescription;

    protected CouponCampaignModel() {
    }

    public CouponCampaignModel(String name, int totalQuota, String benefitDescription) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 이름은 비어있을 수 없습니다.");
        }
        if (totalQuota < 1) {
            throw new CoreException(ErrorType.BAD_REQUEST, "총 발급 한도는 1 이상이어야 합니다.");
        }
        if (benefitDescription == null || benefitDescription.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "혜택 설명은 비어있을 수 없습니다.");
        }
        this.name = name;
        this.totalQuota = totalQuota;
        this.benefitDescription = benefitDescription;
        this.active = true;
        this.issuedCount = 0;
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isSoldOut() {
        return issuedCount >= totalQuota;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public int getTotalQuota() {
        return totalQuota;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public String getBenefitDescription() {
        return benefitDescription;
    }
}
