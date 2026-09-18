package com.loopers.domain.common;

public record Money(long amount) {

    public static final Money ZERO = new Money(0);

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 합니다: " + amount);
        }
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(amount, other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount - other.amount);
    }

    public boolean isLessThan(Money other) {
        return amount < other.amount;
    }

    public boolean isPositive() {
        return amount > 0;
    }

    public boolean isZero() {
        return amount == 0;
    }
}
