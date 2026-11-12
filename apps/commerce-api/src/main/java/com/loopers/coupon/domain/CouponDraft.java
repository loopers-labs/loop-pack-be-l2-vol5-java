package com.loopers.coupon.domain;

import java.time.ZonedDateTime;
import java.util.Set;

/**
 * 캠페인에 담을 쿠폰 한 장의 할인 규칙 (D-5). 검증은 {@link Coupon}이 만들 때 한다.
 */
public record CouponDraft(
    String name,
    DiscountType discountType,
    long discountValue,
    Long maxDiscountAmount,
    long minOrderAmount,
    CouponScope scope,
    Set<Long> applicableProductIds,
    ValidityType validityType,
    ZonedDateTime validFrom,
    ZonedDateTime validUntil,
    Integer validDays,
    boolean stackable
) {
    public CouponDraft {
        applicableProductIds = applicableProductIds == null ? Set.of() : Set.copyOf(applicableProductIds);
    }
}
