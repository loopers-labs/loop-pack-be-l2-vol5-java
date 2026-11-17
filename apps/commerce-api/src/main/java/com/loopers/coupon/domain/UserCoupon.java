package com.loopers.coupon.domain;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;

/**
 * 사용자에게 발급된 쿠폰 한 장 (D-5). 발급(INSERT)은 commerce-streamer가 하고(D-8), commerce-api는 조회만 한다.
 * 두 앱의 매핑은 같은 표를 본다 — 칸을 바꾸면 streamer의 UserCoupon도 함께 바꾼다.
 */
@Entity
@Table(
    name = "user_coupon",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_coupon_coupon_user", columnNames = {"coupon_id", "user_id"}),
    indexes = {
        @Index(name = "idx_user_coupon_user", columnList = "user_id"),
        @Index(name = "idx_user_coupon_campaign_user", columnList = "campaign_id, user_id")
    }
)
public class UserCoupon extends BaseEntity {

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Comment("AVAILABLE | USED | EXPIRED")
    private UserCouponStatus status;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private ZonedDateTime expiresAt;

    @Column(name = "used_at")
    private ZonedDateTime usedAt;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    protected UserCoupon() {}

    /**
     * 테스트·운영 도구에서 발급 결과를 직접 만들 때만 쓴다. 실제 발급은 streamer가 한다.
     */
    public UserCoupon(Coupon coupon, Long userId, ZonedDateTime issuedAt) {
        this.couponId = coupon.getId();
        this.campaignId = coupon.getCampaignId();
        this.userId = userId;
        this.status = UserCouponStatus.AVAILABLE;
        this.issuedAt = issuedAt;
        this.expiresAt = coupon.expiresAtWhenIssuedAt(issuedAt);
    }

    public Long getCouponId() {
        return couponId;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public UserCouponStatus getStatus() {
        return status;
    }

    public ZonedDateTime getIssuedAt() {
        return issuedAt;
    }

    public ZonedDateTime getExpiresAt() {
        return expiresAt;
    }
}
