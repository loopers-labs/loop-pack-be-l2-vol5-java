package com.loopers.domain.promotion;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * AG-07 포함 개념 쿠폰 발급 남은 수량 (TB-17 coupon_issue_stock). 쿠폰 발급 프로모션당 한 행.
 * 처음 값은 발급 한도(TB-16 total_quantity)이고, 발급 TX 의 조건 갱신(remaining > 0 일 때만 -1)만 줄인다 (DR-58).
 * 조건 갱신이 0 행이면 소진이다. 엔티티로 읽어 바꾸지 않는다.
 */
@Entity
@Table(name = "coupon_issue_stock",
    uniqueConstraints = @UniqueConstraint(name = "ux_coupon_issue_stock_promotion", columnNames = "promotion_id"))
public class CouponIssueStockModel extends BaseEntity {

    @Column(name = "promotion_id", nullable = false, updatable = false)
    private Long promotionId;

    @Column(name = "remaining", nullable = false)
    private Integer remaining;

    protected CouponIssueStockModel() {}

    private CouponIssueStockModel(Long promotionId, int remaining) {
        this.promotionId = promotionId;
        this.remaining = remaining;
    }

    /** 발급 한도만큼 남은 수량으로 시작한다. */
    public static CouponIssueStockModel create(Long promotionId, int totalQuantity) {
        if (promotionId == null) {
            throw new CoreException(ErrorType.INVALID_PROMOTION, "쿠폰 발급 수량의 promotionId 가 없습니다.");
        }
        if (totalQuantity < 1) {
            throw new CoreException(ErrorType.INVALID_PROMOTION, "발급 한도는 1 이상이어야 합니다. [totalQuantity = " + totalQuantity + "]");
        }
        return new CouponIssueStockModel(promotionId, totalQuantity);
    }

    public Long getPromotionId() {
        return promotionId;
    }

    public Integer getRemaining() {
        return remaining;
    }
}
