package com.loopers.domain.mall.product;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// 재고 수량 값 객체
public record Stock(int value) {
    public Stock {
        if (value < 0) {
            throw new DomainException(DomainErrorCode.INVALID_STOCK);
        }
    }

    public static Stock of(int value) {
        return new Stock(value);
    }

    public Stock set(int value) {
        return new Stock(value);
    }

    // 차감 가능 여부만 검증하고 값은 바꾸지 않음
    public void ensureCanDecrease(int quantity) {
        if (quantity <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_QUANTITY);
        }
        if (quantity > value) {
            throw new DomainException(DomainErrorCode.INSUFFICIENT_STOCK);
        }
    }

    // 수량만큼 재고 차감
    public Stock decrease(int quantity) {
        ensureCanDecrease(quantity);
        return new Stock(value - quantity);
    }

    public int getValue() {
        return value;
    }
}
