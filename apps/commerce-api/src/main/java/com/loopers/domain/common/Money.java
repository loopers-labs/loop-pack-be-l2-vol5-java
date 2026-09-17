package com.loopers.domain.common;

public record Money(long value) {
    public Money {
        if (value < 0) {
            throw new com.loopers.domain.common.InvalidValueException("금액은 0 이상이어야 합니다.");
        }
    }

    public Money add(Money other) {
        return new Money(Math.addExact(value, other.value));
    }

    public Money multiply(int quantity) {
        if (quantity < 0) {
            throw new com.loopers.domain.common.InvalidValueException("배수는 0 이상이어야 합니다.");
        }
        return new Money(Math.multiplyExact(value, quantity));
    }

    public Money subtract(Money other) {
        if (other.value > value) {
            throw new com.loopers.domain.common.RuleViolationException("잔액이 부족합니다.");
        }
        return new Money(value - other.value);
    }
}
