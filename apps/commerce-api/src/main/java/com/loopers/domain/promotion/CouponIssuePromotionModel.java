package com.loopers.domain.promotion;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AG-07 포함 개념 선착순 쿠폰 발급 설정 (TB-16 coupon_issue_promotion). promotion 과 1:1 이다.
 * promotion_id 는 ID 만 들고 FK 는 두지 않는다. coupon_spec 은 발급할 쿠폰의 동적 속성이며 키를 해석하지 않는다.
 * 수량 규칙: totalQuantity ≥ 1, 1 ≤ perUserLimit ≤ totalQuantity.
 */
@Entity
@Table(name = "coupon_issue_promotion",
    uniqueConstraints = @UniqueConstraint(name = "ux_coupon_issue_promotion_promotion", columnNames = "promotion_id"))
public class CouponIssuePromotionModel extends BaseEntity {

    @Column(name = "promotion_id", nullable = false, updatable = false)
    private Long promotionId;

    /** 선착순 발급 한도. */
    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    /** 1인당 발급 한도. */
    @Column(name = "per_user_limit", nullable = false)
    private Integer perUserLimit;

    /** 발급할 쿠폰의 속성. MySQL JSON, PostgreSQL jsonb. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coupon_spec", nullable = false)
    private Map<String, Object> couponSpec;

    protected CouponIssuePromotionModel() {}

    private CouponIssuePromotionModel(Long promotionId, int totalQuantity, int perUserLimit, Map<String, Object> couponSpec) {
        this.promotionId = promotionId;
        this.totalQuantity = totalQuantity;
        this.perUserLimit = perUserLimit;
        this.couponSpec = couponSpec;
    }

    public static CouponIssuePromotionModel create(Long promotionId, int totalQuantity, int perUserLimit, Map<String, Object> couponSpec) {
        if (promotionId == null) {
            throw new IllegalArgumentException("쿠폰 발급 프로모션의 promotionId 가 없습니다.");
        }
        if (totalQuantity < 1) {
            throw new IllegalArgumentException("발급 한도는 1 이상이어야 합니다. [totalQuantity = " + totalQuantity + "]");
        }
        if (perUserLimit < 1 || perUserLimit > totalQuantity) {
            throw new IllegalArgumentException(
                "1인당 한도는 1 이상, 발급 한도 이하여야 합니다. [perUserLimit = " + perUserLimit + ", totalQuantity = " + totalQuantity + "]");
        }
        if (couponSpec == null || couponSpec.isEmpty()) {
            throw new IllegalArgumentException("발급할 쿠폰 속성(couponSpec)이 비어 있습니다.");
        }
        return new CouponIssuePromotionModel(promotionId, totalQuantity, perUserLimit, new LinkedHashMap<>(couponSpec));
    }

    public Long getPromotionId() {
        return promotionId;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public Integer getPerUserLimit() {
        return perUserLimit;
    }

    /** 읽기 전용. */
    public Map<String, Object> getCouponSpec() {
        return Collections.unmodifiableMap(couponSpec);
    }
}
