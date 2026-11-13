package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

@Entity
@Table(
    name = "coupon",
    indexes = @Index(name = "idx_coupon_user_issued_at_id", columnList = "user_id, issued_at, id"),
    uniqueConstraints = @UniqueConstraint(
        name = "uk_coupon_campaign_user",
        columnNames = {"campaign_id", "user_id"}
    )
)
public class Coupon extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;

    @Column(nullable = false, length = 255)
    private String benefit;

    protected Coupon() {
    }

    public Coupon(Long userId, Long campaignId, String benefit, ZonedDateTime issuedAt) {
        this.userId = userId;
        this.campaignId = campaignId;
        this.benefit = benefit;
        this.issuedAt = issuedAt;
    }

    public Long getId() {
        return super.getId();
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public ZonedDateTime getIssuedAt() {
        return issuedAt;
    }

    public String getBenefit() {
        return benefit;
    }
}
