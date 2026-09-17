package com.loopers.domain.product;

public record Stock(int value) {
    public Stock {
        if (value < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다.");
        }
    }

    public Stock decrease(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("차감량은 양수여야 합니다.");
        }
        if (amount > value) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        return new Stock(value - amount);
    }
}
