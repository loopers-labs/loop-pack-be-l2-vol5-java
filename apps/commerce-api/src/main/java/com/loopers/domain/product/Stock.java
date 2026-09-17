package com.loopers.domain.product;

public record Stock(int value) {
    public Stock {
        if (value < 0) {
            throw new com.loopers.domain.common.InvalidValueException("재고는 0 이상이어야 합니다.");
        }
    }

    public Stock decrease(int amount) {
        if (amount <= 0) {
            throw new com.loopers.domain.common.InvalidValueException("차감량은 양수여야 합니다.");
        }
        if (amount > value) {
            throw new com.loopers.domain.common.RuleViolationException("재고가 부족합니다.");
        }
        return new Stock(value - amount);
    }
}
