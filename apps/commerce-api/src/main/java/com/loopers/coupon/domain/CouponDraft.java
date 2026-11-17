package com.loopers.coupon.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

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
        // Set.of()의 contains(null)은 NPE를 던지므로 원소를 하나씩 본다
        if (applicableProductIds != null && applicableProductIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new CoreException(ErrorType.BAD_REQUEST, "적용 상품 id가 비어 있습니다.");
        }
        applicableProductIds = applicableProductIds == null ? Set.of() : Set.copyOf(applicableProductIds);
    }
}
