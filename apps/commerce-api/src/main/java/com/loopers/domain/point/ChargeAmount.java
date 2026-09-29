package com.loopers.domain.point;

import com.loopers.domain.common.Money;

public record ChargeAmount(long value) {

    public ChargeAmount {
        if (value <= 0) {
            throw new IllegalArgumentException("충전액은 양의 정수여야 합니다: " + value);
        }
    }

    public static ChargeAmount of(long value) {
        return new ChargeAmount(value);
    }

    public Money toMoney() {
        return Money.of(value);
    }
}
