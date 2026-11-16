package com.loopers.domain.coupon;

/**
 * 정액은 원 단위 양의 long, 정률은 1~100 정수다. 상품 가격 상한·Money 의 0 허용은 쿠폰 혜택에 적용하지 않는다.
 */
public enum CouponBenefitType {
    FIXED_AMOUNT,
    PERCENTAGE;

    private static final long MAX_PERCENTAGE = 100L;

    public boolean accepts(long value) {
        return switch (this) {
            case FIXED_AMOUNT -> value >= 1L;
            case PERCENTAGE -> value >= 1L && value <= MAX_PERCENTAGE;
        };
    }
}
