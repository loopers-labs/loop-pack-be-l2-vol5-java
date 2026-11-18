package com.loopers.domain.promotion;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * AG-07 포함 개념 발급된 쿠폰 (TB-18 issued_coupon). 발급 내역이 곧 사용자의 쿠폰이다 (DR-57).
 * (user_id, promotion_id) 고유: 한 사용자는 한 프로모션에서 한 장만 받는다. 동시 중복 요청은 이 제약이 막는다.
 * 쿠폰 내용은 복사하지 않고 TB-16 coupon_spec 을 조회 때 함께 읽는다. 발급 시각은 created_at 이다.
 */
@Entity
@Table(name = "issued_coupon",
    uniqueConstraints = @UniqueConstraint(name = "ux_issued_coupon_user_promotion", columnNames = {"user_id", "promotion_id"}))
public class IssuedCouponModel extends BaseEntity {

    /** 받은 사용자 (BC-01). ID 만. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "promotion_id", nullable = false, updatable = false)
    private Long promotionId;

    protected IssuedCouponModel() {}

    private IssuedCouponModel(Long userId, Long promotionId) {
        this.userId = userId;
        this.promotionId = promotionId;
    }

    public static IssuedCouponModel create(Long userId, Long promotionId) {
        if (userId == null || promotionId == null) {
            throw new IllegalArgumentException("발급 쿠폰의 userId·promotionId 가 없습니다.");
        }
        return new IssuedCouponModel(userId, promotionId);
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPromotionId() {
        return promotionId;
    }

    public ZonedDateTime getIssuedAt() {
        return getCreatedAt();
    }
}
