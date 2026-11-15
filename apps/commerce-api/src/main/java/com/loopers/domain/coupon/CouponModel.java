package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 사용자가 보유한 쿠폰 한 건. UNIQUE(campaign_id, user_id)가 "캠페인당 사용자 한 장"의 최종 보호다.
 * 혜택 정보는 발급 시점의 값을 복사해 저장한다.
 */
@Entity
@Table(
    name = "coupon",
    uniqueConstraints = @UniqueConstraint(name = "uk_coupon_campaign_user", columnNames = {"campaign_id", "user_id"}),
    indexes = @Index(name = "idx_coupon_user_issued", columnList = "user_id, issued_at DESC, id DESC")
)
public class CouponModel extends BaseEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "benefit_description", nullable = false, updatable = false)
    private String benefitDescription;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private ZonedDateTime issuedAt;

    protected CouponModel() {
    }

    public CouponModel(Long campaignId, Long userId, String benefitDescription, ZonedDateTime issuedAt) {
        if (campaignId == null || userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "캠페인과 사용자는 비어있을 수 없습니다.");
        }
        if (benefitDescription == null || issuedAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "혜택 설명과 발급 시각은 비어있을 수 없습니다.");
        }
        this.campaignId = campaignId;
        this.userId = userId;
        this.benefitDescription = benefitDescription;
        this.issuedAt = issuedAt;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getBenefitDescription() {
        return benefitDescription;
    }

    public ZonedDateTime getIssuedAt() {
        return issuedAt;
    }
}
