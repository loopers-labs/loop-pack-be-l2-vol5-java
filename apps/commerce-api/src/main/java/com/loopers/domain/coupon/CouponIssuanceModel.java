package com.loopers.domain.coupon;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(
    name = "coupon_issuances",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_coupon_issuances_campaign_user",
        columnNames = {"campaign_id", "user_id"}
    )
)
public class CouponIssuanceModel extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;

    protected CouponIssuanceModel() {
    }

    public CouponIssuanceModel(Long campaignId, Long userId) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.issuedAt = ZonedDateTime.now();
    }

    public Long getCampaignId() { return campaignId; }
    public Long getUserId() { return userId; }
    public ZonedDateTime getIssuedAt() { return issuedAt; }
}
