package com.loopers.domain.product;

import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;

public record Price(long value) {

    private static final long MIN = 1L;
    private static final long MAX = 10_000_000L;

    public Price {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                "가격은 " + MIN + "원 이상 " + MAX + "원 이하여야 합니다: " + value);
        }
    }

    public static Price of(long value) {
        return new Price(value);
    }

    public Money times(Quantity quantity) {
        return Money.of(Math.multiplyExact(value, quantity.value()));
    }

    public Money toMoney() {
        return Money.of(value);
    }
}
