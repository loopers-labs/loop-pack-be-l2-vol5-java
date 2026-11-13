package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * 쿠폰 혜택(예: "5,000원 할인권", 5000). 캠페인이 지금 혜택을 갖고, 발급된 쿠폰은 발급 순간의 값을 복사해 갖는다
 * (docs/week8/decisions.md D-15, INV-806). 값 객체라 바꾸지 않고 새로 만든다 — 캠페인의 혜택을 바꿔도 이미 복사된 쿠폰의
 * 값은 따라 바뀌지 않는다.
 *
 * <p>할인 적용 로직은 없다. 이번 범위는 "무엇을 받았는지"를 남기는 것까지다.
 */
@Embeddable
public class CouponBenefit {

    @Column(name = "benefit_description", nullable = false)
    private String description;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    protected CouponBenefit() {}

    private CouponBenefit(String description, long discountAmount) {
        this.description = description;
        this.discountAmount = discountAmount;
    }

    /**
     * @throws CoreException BAD_REQUEST — 설명이 비었거나 할인 금액이 양수가 아니다.
     */
    public static CouponBenefit of(String description, long discountAmount) {
        if (description == null || description.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 혜택 설명은 비어있을 수 없습니다.");
        }
        if (discountAmount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "쿠폰 할인 금액은 양수여야 합니다.");
        }
        return new CouponBenefit(description, discountAmount);
    }

    /** 같은 값을 가진 새 객체. 발급 때 캠페인 혜택을 쿠폰 행으로 복사할 때 쓴다 — 두 엔티티가 같은 인스턴스를 나눠 갖지 않게 한다. */
    CouponBenefit copy() {
        return new CouponBenefit(description, discountAmount);
    }

    public String getDescription() {
        return description;
    }

    public long getDiscountAmount() {
        return discountAmount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CouponBenefit other)) {
            return false;
        }
        return discountAmount == other.discountAmount && Objects.equals(description, other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, discountAmount);
    }
}
