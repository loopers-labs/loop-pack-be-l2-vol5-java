package com.loopers.domain.common;

public record Quantity(int value) {

    public static final Quantity ZERO = new Quantity(0);

    public Quantity {
        if (value < 0) {
            throw new IllegalArgumentException("수량은 0 이상이어야 합니다: " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    public Quantity minus(Quantity other) {
        return new Quantity(value - other.value);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }

    public boolean isLessThan(Quantity other) {
        return value < other.value;
    }

    public boolean isZero() {
        return value == 0;
    }

    public boolean isPositive() {
        return value > 0;
    }
}
