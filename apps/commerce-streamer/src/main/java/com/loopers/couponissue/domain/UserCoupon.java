package com.loopers.couponissue.domain;

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
 * 사용자에게 발급된 쿠폰 한 장 (D-5). 쓰는 쪽은 streamer(발급)뿐이고, commerce-api는 조회만 한다.
 * UNIQUE(coupon_id, user_id)가 1인 1장의 최종 방어다 — 같은 메시지를 두 번 처리해도 두 번째 INSERT가 막힌다 (CPN-08).
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
    @Comment("어느 캠페인에서 받았나 — 이미 발급됐는지를 캠페인·사용자로 찾는다")
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Comment("AVAILABLE | USED | EXPIRED. 이번 주는 발급만 하므로 AVAILABLE")
    private Status status;

    @Column(name = "issued_at", nullable = false)
    private ZonedDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    @Comment("발급 때 계산해 저장한다 (CPN-07)")
    private ZonedDateTime expiresAt;

    @Column(name = "used_at")
    private ZonedDateTime usedAt;

    @Column(name = "used_order_id")
    private Long usedOrderId;

    protected UserCoupon() {}

    private UserCoupon(IssuableCoupon coupon, Long campaignId, Long userId, ZonedDateTime issuedAt) {
        this.couponId = coupon.getId();
        this.campaignId = campaignId;
        this.userId = userId;
        this.status = Status.AVAILABLE;
        this.issuedAt = issuedAt;
        this.expiresAt = coupon.expiresAtWhenIssuedAt(issuedAt);
    }

    public static UserCoupon issue(IssuableCoupon coupon, Long campaignId, Long userId, ZonedDateTime issuedAt) {
        return new UserCoupon(coupon, campaignId, userId, issuedAt);
    }

    public Long getCouponId() {
        return couponId;
    }

    public Long getUserId() {
        return userId;
    }

    public ZonedDateTime getExpiresAt() {
        return expiresAt;
    }

    public enum Status {
        AVAILABLE,
        USED,
        EXPIRED
    }
}
