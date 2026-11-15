package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(
    name = "coupon",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_coupon_campaign_user",
        columnNames = {"campaign_id", "user_id"}
    )
)
public class CouponModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Long campaignId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "benefit_description", nullable = false, updatable = false, length = 1000)
    private String benefitDescription;

    /** 작업 큐에서 이 쿠폰을 만든 접수의 예약 값(R:UUID). 재처리에서 "이 작업이 만든 쿠폰"인지 가린다. 동기 발급은 없다. */
    @Column(name = "reservation_id", updatable = false, length = 64)
    private String reservationId;

    protected CouponModel() {}

    public CouponModel(Long userId, Long campaignId, Instant issuedAt, String benefitDescription) {
        this(userId, campaignId, issuedAt, benefitDescription, null);
    }

    public CouponModel(Long userId, Long campaignId, Instant issuedAt, String benefitDescription, String reservationId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        if (campaignId == null || campaignId <= 0) {
            throw new IllegalArgumentException("캠페인 ID는 양수여야 합니다.");
        }
        if (issuedAt == null) {
            throw new IllegalArgumentException("발급 시각은 필수입니다.");
        }
        if (benefitDescription == null || benefitDescription.isBlank()) {
            throw new IllegalArgumentException("쿠폰 혜택 설명은 비어있을 수 없습니다.");
        }
        if (benefitDescription.codePointCount(0, benefitDescription.length()) > 1000) {
            throw new IllegalArgumentException("쿠폰 혜택 설명은 1,000자 이하여야 합니다.");
        }
        this.userId = userId;
        this.campaignId = campaignId;
        this.issuedAt = issuedAt;
        this.benefitDescription = benefitDescription;
        this.reservationId = reservationId;
    }
}
