package com.loopers.domain.coupon;

public record DiscountAmount(long value) {

    public DiscountAmount {
        if (value <= 0) {
            throw new IllegalArgumentException("할인 금액은 양의 정수여야 합니다: " + value);
        }
    }

    public static DiscountAmount of(long value) {
        return new DiscountAmount(value);
    }
}
