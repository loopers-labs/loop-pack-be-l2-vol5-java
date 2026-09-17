package com.loopers.domain.order;

import com.loopers.domain.common.InvalidValueException;

public record Quantity(int value) {
    public Quantity {
        if (value <= 0) { throw new InvalidValueException("주문 수량은 양수여야 합니다."); }
    }
}
